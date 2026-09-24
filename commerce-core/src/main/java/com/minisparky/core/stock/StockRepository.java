package com.minisparky.core.stock;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    public record StockLevel(String sku, String location, int onHand, int reserved, int available) {}

    private final JdbcTemplate jdbc;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StockLevel> find(String sku, String location) {
        return jdbc.query(
                "SELECT sku, location_id, on_hand, reserved FROM stock WHERE sku = ? AND location_id = ?",
                (rs, i) -> new StockLevel(rs.getString("sku"), rs.getString("location_id"),
                        rs.getInt("on_hand"), rs.getInt("reserved"),
                        rs.getInt("on_hand") - rs.getInt("reserved")),
                sku, location).stream().findFirst();
    }

    public boolean reserve(String sku, String location, int qty) {
        return jdbc.update("""
                UPDATE stock SET reserved = reserved + ?
                WHERE sku = ? AND location_id = ? AND (on_hand - reserved) >= ?
                """, qty, sku, location, qty) == 1;
    }

    public boolean release(String sku, String location, int qty) {
        return jdbc.update("""
                UPDATE stock SET reserved = reserved - ?
                WHERE sku = ? AND location_id = ? AND reserved >= ?
                """, qty, sku, location, qty) == 1;
    }

    public boolean commit(String sku, String location, int qty) {
        return jdbc.update("""
                UPDATE stock SET on_hand = on_hand - ?, reserved = reserved - ?
                WHERE sku = ? AND location_id = ? AND reserved >= ?
                """, qty, qty, sku, location, qty) == 1;
    }
}