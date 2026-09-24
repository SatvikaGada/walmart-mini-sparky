package com.minisparky.core.cart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CartExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CartExpiryJob.class);

    private final CartService carts;

    public CartExpiryJob(CartService carts) {
        this.carts = carts;
    }

    @Scheduled(fixedDelayString = "${app.expiry-interval-ms:30000}")
    public void scheduled() {
        int n = expireDue();
        if (n > 0) log.info("Expired {} cart(s) and released their stock", n);
    }

    public int expireDue() {
        int n = 0;
        while (n < 500 && carts.expireNextDueCart()) {
            n++;
        }
        return n;
    }
}