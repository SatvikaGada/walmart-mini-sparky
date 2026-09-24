package com.minisparky.core.audit;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.error.ApiException;

@Service
public class AuditService {

    public record AuditRow(long id, String ts, String sessionId, String actor, String action,
                           String cartId, String sku, Integer qty, String decision, String reason) {}

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String sessionId, String actor, String action, String cartId,
                       String sku, Integer qty, String decision, String reason) {
        jdbc.update("""
                INSERT INTO audit_log (session_id, actor, action, request, decision, reason)
                VALUES (?, ?, ?, jsonb_build_object('cartId', ?::text, 'sku', ?::text, 'qty', ?::int), ?, ?)
                """, sessionId, actor, action, cartId, sku, qty, decision, reason);
    }

    public <T> T run(Caller caller, String action, String cartId, String sku, Integer qty, Supplier<T> op) {
        try {
            T result = op.get();
            record(caller.sessionId(), caller.actor(), action, cartId, sku, qty, "ALLOWED", null);
            return result;
        } catch (ApiException e) {
            record(caller.sessionId(), caller.actor(), action, cartId, sku, qty, "BLOCKED", e.getCode());
            throw e;
        }
    }

    public List<AuditRow> forSession(String sessionId) {
        return jdbc.query("""
                SELECT id, ts, session_id, actor, action,
                       request->>'cartId' AS cart_id, request->>'sku' AS sku,
                       (request->>'qty')::int AS qty, decision, reason
                FROM audit_log WHERE session_id = ? ORDER BY id DESC LIMIT 200
                """,
                (rs, i) -> new AuditRow(rs.getLong("id"), rs.getTimestamp("ts").toInstant().toString(),
                        rs.getString("session_id"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("cart_id"), rs.getString("sku"), (Integer) rs.getObject("qty"),
                        rs.getString("decision"), rs.getString("reason")),
                sessionId);
    }
}