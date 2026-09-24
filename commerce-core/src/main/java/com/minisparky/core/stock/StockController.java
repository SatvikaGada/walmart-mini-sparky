package com.minisparky.core.stock;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.minisparky.core.error.ApiException;
import com.minisparky.core.stock.StockRepository.StockLevel;

@RestController
public class StockController {

    private final StockRepository stock;

    public StockController(StockRepository stock) {
        this.stock = stock;
    }

    @GetMapping("/api/stock/{sku}")
    public StockLevel get(@PathVariable String sku,
                          @RequestParam(defaultValue = "ONLINE") String location) {
        return stock.find(sku, location).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No stock row for " + sku));
    }
}