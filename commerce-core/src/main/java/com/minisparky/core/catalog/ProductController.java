package com.minisparky.core.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.minisparky.core.catalog.ProductDtos.ProductDetail;
import com.minisparky.core.catalog.ProductDtos.ProductSummary;
import com.minisparky.core.error.ApiException;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int MAX_LIMIT = 20;

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping("/search")
    public List<ProductSummary> search(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(required = false) Integer maxPricePaise,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(defaultValue = "20") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return products.search(q, category, maxPricePaise, tag, capped);
    }

    @GetMapping("/{sku}")
    public ProductDetail detail(@PathVariable String sku) {
        return products.findDetail(sku).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown product: " + sku));
    }
}