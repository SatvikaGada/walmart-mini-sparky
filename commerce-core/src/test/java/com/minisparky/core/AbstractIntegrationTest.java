package com.minisparky.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.cart.CartService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.expiry-interval-ms=3600000")
public abstract class AbstractIntegrationTest {

    protected static final String AGENT_KEY = "dev-agent-key-change-me";

    static final PostgreSQLContainer POSTGRES;

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        POSTGRES = new PostgreSQLContainer("postgres:16");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CartService carts;
    @Value("${local.server.port}") protected int port;

    private final HttpClient http = HttpClient.newHttpClient();

    protected record Resp(int status, String body) {}

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE audit_log, idempotency_keys, orders, cart_items, carts");
    }

    protected void seedProduct(String sku, int pricePaise, int maxQty, int onHand) {
        jdbc.update("""
                INSERT INTO products (sku, name, category, description, price_paise, max_qty_per_order)
                VALUES (?, ?, 'test', 'test product', ?, ?)
                ON CONFLICT (sku) DO UPDATE SET price_paise = EXCLUDED.price_paise,
                                                max_qty_per_order = EXCLUDED.max_qty_per_order
                """, sku, "Test " + sku, pricePaise, maxQty);
        jdbc.update("""
                INSERT INTO stock (sku, location_id, on_hand, reserved) VALUES (?, 'ONLINE', ?, 0)
                ON CONFLICT (sku, location_id) DO UPDATE SET on_hand = EXCLUDED.on_hand, reserved = 0
                """, sku, onHand);
    }

    protected int reserved(String sku) {
        return jdbc.queryForObject("SELECT reserved FROM stock WHERE sku = ?", Integer.class, sku);
    }

    protected int onHand(String sku) {
        return jdbc.queryForObject("SELECT on_hand FROM stock WHERE sku = ?", Integer.class, sku);
    }

    protected int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    protected int blockedCount(String sessionId, String reason) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE session_id = ? AND decision = 'BLOCKED' AND reason = ?",
                Integer.class, sessionId, reason);
    }

    protected String newSession() {
        return UUID.randomUUID().toString();
    }

    protected Caller agent(String sessionId) {
        return new Caller("agent", sessionId);
    }

    protected Resp call(String method, String path, String body, String... headerPairs) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
            for (int i = 0; i < headerPairs.length; i += 2) {
                b.header(headerPairs[i], headerPairs[i + 1]);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}