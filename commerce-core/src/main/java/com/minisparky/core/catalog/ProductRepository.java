package com.minisparky.core.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.minisparky.core.catalog.ProductDtos.ProductDetail;
import com.minisparky.core.catalog.ProductDtos.ProductSummary;
import com.minisparky.core.catalog.ProductDtos.Review;

@Repository
public class ProductRepository {

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductSummary> search(String q, String category, Integer maxPricePaise,
                                       String tag, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.sku, p.name, p.category, p.price_paise, p.tags,
                       COALESCE(s.on_hand - s.reserved, 0) AS available
                FROM products p
                LEFT JOIN stock s ON s.sku = p.sku AND s.location_id = 'ONLINE'
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();

        if (q != null && !q.isBlank()) {
            String term = q.trim();
            sql.append(" AND (p.name ILIKE ? OR p.description ILIKE ? OR ? = ANY(p.tags))");
            args.add("%" + term + "%");
            args.add("%" + term + "%");
            args.add(term.toLowerCase());
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND p.category = ?");
            args.add(category.trim().toLowerCase());
        }
        if (maxPricePaise != null) {
            sql.append(" AND p.price_paise <= ?");
            args.add(maxPricePaise);
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND ? = ANY(p.tags)");
            args.add(tag.trim().toLowerCase());
        }
        sql.append(" ORDER BY p.sku LIMIT ?");
        args.add(limit);

        return jdbc.query(sql.toString(), ProductRepository::mapSummary, args.toArray());
    }

    public Optional<ProductDetail> findDetail(String sku) {
        List<Review> reviews = jdbc.query(
                "SELECT author, body FROM product_reviews WHERE sku = ? ORDER BY id LIMIT 3",
                (rs, i) -> new Review(rs.getString("author"), rs.getString("body")),
                sku);

        List<ProductDetail> rows = jdbc.query("""
                SELECT p.sku, p.name, p.category, p.description, p.price_paise, p.tags,
                       p.max_qty_per_order,
                       COALESCE(s.on_hand - s.reserved, 0) AS available
                FROM products p
                LEFT JOIN stock s ON s.sku = p.sku AND s.location_id = 'ONLINE'
                WHERE p.sku = ?
                """,
                (rs, i) -> new ProductDetail(
                        rs.getString("sku"), rs.getString("name"), rs.getString("category"),
                        rs.getString("description"), rs.getInt("price_paise"), tags(rs),
                        rs.getInt("max_qty_per_order"), rs.getInt("available"), reviews),
                sku);
        return rows.stream().findFirst();
    }

    private static ProductSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new ProductSummary(rs.getString("sku"), rs.getString("name"),
                rs.getString("category"), rs.getInt("price_paise"), tags(rs),
                rs.getInt("available"));
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        return List.of((String[]) rs.getArray("tags").getArray());
    }
}