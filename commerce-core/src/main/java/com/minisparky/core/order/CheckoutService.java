package com.minisparky.core.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.cart.CartService;
import com.minisparky.core.cart.CartService.CartRow;
import com.minisparky.core.cart.CartService.ItemRow;
import com.minisparky.core.error.ApiException;
import com.minisparky.core.policy.PolicyGuard;
import com.minisparky.core.stock.StockRepository;

@Service
public class CheckoutService {

    public record OrderResponse(String orderId, String cartId, int totalPaise, String status) {}

    private record StoredKey(String requestHash, String status, String orderId, String cartId, int totalPaise) {}

    private final JdbcTemplate jdbc;
    private final CartService carts;
    private final StockRepository stock;
    private final PolicyGuard guard;

    public CheckoutService(JdbcTemplate jdbc, CartService carts, StockRepository stock, PolicyGuard guard) {
        this.jdbc = jdbc;
        this.carts = carts;
        this.stock = stock;
        this.guard = guard;
    }

    @Transactional
    public OrderResponse checkout(Caller caller, String cartId, String idemKey) {
        String scope = "checkout:" + caller.sessionId();
        String requestHash = sha256("POST /api/carts/" + cartId + "/checkout");

        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (idem_key, scope, request_hash, status)
                VALUES (?, ?, ?, 'IN_PROGRESS') ON CONFLICT DO NOTHING
                """, idemKey, scope, requestHash);
        if (inserted == 0) {
            return replay(idemKey, scope, requestHash);
        }

        CartRow cart = carts.lockOwnedCart(cartId, caller.sessionId());
        guard.requireOpen(cart.status(), cart.expired());

        List<ItemRow> items = carts.itemRows(cart.id());
        if (items.isEmpty()) {
            throw new ApiException(PolicyGuard.UNPROCESSABLE, "EMPTY_CART", "Cart is empty");
        }
        long total = items.stream().mapToLong(i -> (long) i.qty() * i.unitPricePaise()).sum();
        guard.checkTotal(total, cart.budgetPaise());

        for (ItemRow item : items) {
            if (!stock.commit(item.sku(), item.location(), item.qty())) {
                throw new ApiException(HttpStatus.CONFLICT, "STOCK_CONFLICT",
                        "Stock could not be committed for " + item.sku(), Map.of("sku", item.sku()));
            }
        }

        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, cart_id, session_id, total_paise) VALUES (?, ?, ?, ?)",
                orderId, cart.id(), caller.sessionId(), (int) total);
        jdbc.update("UPDATE carts SET status = 'CHECKED_OUT' WHERE id = ?", cart.id());
        jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'DONE',
                    response_json = jsonb_build_object('orderId', ?::text, 'cartId', ?::text,
                                                       'totalPaise', ?::int, 'status', 'PLACED')
                WHERE idem_key = ? AND scope = ?
                """, orderId.toString(), cart.id().toString(), (int) total, idemKey, scope);
        return new OrderResponse(orderId.toString(), cart.id().toString(), (int) total, "PLACED");
    }

    private OrderResponse replay(String key, String scope, String requestHash) {
        StoredKey stored = jdbc.query("""
                        SELECT request_hash, status, response_json->>'orderId', response_json->>'cartId',
                               (response_json->>'totalPaise')::int
                        FROM idempotency_keys WHERE idem_key = ? AND scope = ?
                        """,
                        (rs, i) -> new StoredKey(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getInt(5)),
                        key, scope).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Idempotency row vanished"));
        if (!stored.requestHash().equals(requestHash)) {
            throw new ApiException(PolicyGuard.UNPROCESSABLE, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
        if (!"DONE".equals(stored.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Original request still running");
        }
        return new OrderResponse(stored.orderId(), stored.cartId(), stored.totalPaise(), "PLACED");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}