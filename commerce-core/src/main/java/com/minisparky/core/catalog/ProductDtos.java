package com.minisparky.core.catalog;

import java.util.List;

public final class ProductDtos {

    private ProductDtos() {}

    public record ProductSummary(String sku, String name, String category,
                                 int pricePaise, List<String> tags, int available) {}

    public record Review(String author, String body) {}

    public record ProductDetail(String sku, String name, String category, String description,
                                int pricePaise, List<String> tags, int maxQtyPerOrder,
                                int available, List<Review> reviews) {}
}