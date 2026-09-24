package com.minisparky.core.cart;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.config.AppProperties;
import com.minisparky.core.error.ApiException;
import com.minisparky.core.policy.PolicyGuard;
import com.minisparky.core.stock.StockRepository;

@Service
public class CartService {

    public record CartCreated(String cartId, String expiresAt) {}
    public record CartItemView(String sku, String name, int qty, int unitPricePaise, long linePaise) {}
    public record CartView(String cartId, String status, Integer budgetPaise, String expiresAt,
                           List<CartItemView> items, long totalPaise, long remainingBudgetPaise) {}
    public record CartStatus(String cartId, String status) {}
    public record CartRow(UUID id, String sessionId, String status, Integer budgetPaise,
                          Instant expiresAt, boolean expired) {}
    public record ItemRow(String sku, String location, int qty, int unitPricePaise) {}

    private static final String LOC = "ONLINE";

    private final JdbcTemplate jdbc;
    private final StockRepository stock;
    private final PolicyGuard guard;
    private final AppProperties props;

    public CartService(JdbcTemplate jdbc, StockRepository stock, PolicyGuard guard, AppProperties props) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.guard = guard;
        this.props = props;
    }

    @Transactional
    public CartCreated createCart(Caller caller, Integer budgetPaise) {
        if (budgetPaise != null && budgetPaise <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "budgetPaise must be positive");
        }
        UUID id = UUID.randomUUID();
        Timestamp expires = jdbc.queryForObject("""
                INSERT INTO carts (id, session_id, status, budget_paise, expires_at)
                VALUES (?, ?, 'OPEN', ?, now() + ?::int * interval '1 second')
                RETURNING expires_at
                """, Timestamp.class, id, caller.sessionId(), budgetPaise, props.cartTtlSeconds());
        return new CartCreated(id.toString(), expires.toInstant().toString());
    }

    @Transactional
    public CartView addItem(Caller caller, String cartId, String sku, int qty) {
        if (qty < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "qty must be at least 1");
        }
        CartRow cart = lockOwnedCart(cartId, caller.sessionId());
        guard.requireOpen(cart.status(), cart.expired());

        int[] product = jdbc.query("SELECT price_paise, max_qty_per_order FROM products WHERE sku = ?",
                        (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, sku)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown product: " + sku));
        int price = product[0];
        int maxQty = product[1];

        int existing = jdbc.query(
                        "SELECT qty FROM cart_items WHERE cart_id = ? AND sku = ? AND location_id = ?",
                        (rs, i) -> rs.getInt(1), cart.id(), sku, LOC)
                .stream().findFirst().orElse(0);
        Integer lines = jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id = ?", Integer.class, cart.id());

        guard.checkAdd(existing + qty, maxQty, existing == 0, lines == null ? 0 : lines,
                currentTotal(cart.id()) + (long) qty * price, cart.budgetPaise());

        if (!stock.reserve(sku, LOC, qty)) {
            throw new ApiException(HttpStatus.CONFLICT, "OUT_OF_STOCK", "Not enough stock for " + sku,
                    Map.of("sku", sku, "requested", qty));
        }
        jdbc.update("""
                INSERT INTO cart_items (cart_id, sku, location_id, qty, unit_price_paise)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (cart_id, sku, location_id) DO UPDATE SET qty = cart_items.qty + EXCLUDED.qty
                """, cart.id(), sku, LOC, qty, price);
        return buildView(cart);
    }

    @Transactional
    public CartView removeItem(Caller caller, String cartId, String sku) {
        CartRow cart = lockOwnedCart(cartId, caller.sessionId());
        guard.requireOpen(cart.status(), cart.expired());
        List<Integer> removed = jdbc.query(
                "DELETE FROM cart_items WHERE cart_id = ? AND sku = ? AND location_id = ? RETURNING qty",
                (rs, i) -> rs.getInt(1), cart.id(), sku, LOC);
        if (removed.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Item not in cart: " + sku);
        }
        releaseOrFail(sku, LOC, removed.get(0));
        return buildView(cart);
    }

    @Transactional(readOnly = true)
    public CartView getCart(Caller caller, String cartId) {
        return buildView(fetchCart(cartId, caller.sessionId(), false));
    }

    @Transactional
    public CartStatus cancel(Caller caller, String cartId) {
        CartRow cart = lockOwnedCart(cartId, caller.sessionId());
        if (!"OPEN".equals(cart.status())) {
            throw new ApiException(PolicyGuard.UNPROCESSABLE, "CART_NOT_OPEN", "Cart is " + cart.status());
        }
        releaseAll(cart.id());
        jdbc.update("UPDATE carts SET status = 'CANCELLED' WHERE id = ?", cart.id());
        return new CartStatus(cart.id().toString(), "CANCELLED");
    }

    /** Expires ONE due cart per transaction. SKIP LOCKED lets several app instances share the work. */
    @Transactional
    public boolean expireNextDueCart() {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM carts WHERE status = 'OPEN' AND expires_at < now()
                ORDER BY expires_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, i) -> rs.getObject(1, UUID.class));
        if (ids.isEmpty()) return false;
        releaseAll(ids.get(0));
        jdbc.update("UPDATE carts SET status = 'EXPIRED' WHERE id = ?", ids.get(0));
        return true;
    }

    public CartRow lockOwnedCart(String cartId, String sessionId) {
        return fetchCart(cartId, sessionId, true);
    }

    public List<ItemRow> itemRows(UUID cartId) {
        return jdbc.query("""
                SELECT sku, location_id, qty, unit_price_paise FROM cart_items
                WHERE cart_id = ? ORDER BY sku
                """, (rs, i) -> new ItemRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)), cartId);
    }

    private CartRow fetchCart(String cartId, String sessionId, boolean forUpdate) {
        UUID id;
        try {
            id = UUID.fromString(cartId);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        String sql = "SELECT id, session_id, status, budget_paise, expires_at, expires_at < now() AS expired "
                + "FROM carts WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "");
        CartRow cart = jdbc.query(sql, (rs, i) -> mapCart(rs), id)
                .stream().findFirst().orElseThrow(CartService::notFound);
        if (!cart.sessionId().equals(sessionId)) {
            throw notFound();
        }
        return cart;
    }

    private static CartRow mapCart(ResultSet rs) throws SQLException {
        return new CartRow(rs.getObject("id", UUID.class), rs.getString("session_id"), rs.getString("status"),
                (Integer) rs.getObject("budget_paise"), rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("expired"));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown cart");
    }

    private long currentTotal(UUID cartId) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty::bigint * unit_price_paise), 0) FROM cart_items WHERE cart_id = ?",
                Long.class, cartId);
        return total == null ? 0 : total;
    }

    private void releaseAll(UUID cartId) {
        for (ItemRow item : itemRows(cartId)) {
            releaseOrFail(item.sku(), item.location(), item.qty());
        }
    }

    private void releaseOrFail(String sku, String location, int qty) {
        if (!stock.release(sku, location, qty)) {
            throw new IllegalStateException("Stock accounting mismatch while releasing " + sku);
        }
    }

    private CartView buildView(CartRow cart) {
        List<CartItemView> items = jdbc.query("""
                SELECT ci.sku, p.name, ci.qty, ci.unit_price_paise
                FROM cart_items ci JOIN products p ON p.sku = ci.sku
                WHERE ci.cart_id = ? ORDER BY ci.sku
                """, (rs, i) -> new CartItemView(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                (long) rs.getInt(3) * rs.getInt(4)), cart.id());
        long total = items.stream().mapToLong(CartItemView::linePaise).sum();
        String status = "OPEN".equals(cart.status()) && cart.expired() ? "EXPIRED" : cart.status();
        return new CartView(cart.id().toString(), status, cart.budgetPaise(), cart.expiresAt().toString(),
                items, total, guard.effectiveLimit(cart.budgetPaise()) - total);
    }
}