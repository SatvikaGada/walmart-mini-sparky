package com.minisparky.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.error.ApiException;

class StockConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void hundredBuyersFightForTenUnits() throws Exception {
        seedProduct("TST-HOT", 10000, 10, 10);
        int buyers = 100;
        List<Caller> callers = new ArrayList<>();
        List<String> cartIds = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            Caller c = agent(newSession());
            callers.add(c);
            cartIds.add(carts.createCart(c, null).cartId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    carts.addItem(callers.get(n), cartIds.get(n), "TST-HOT", 1);
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if ("OUT_OF_STOCK".equals(e.getCode())) outOfStock.incrementAndGet();
                    else other.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<Object> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(10);
        assertThat(outOfStock.get()).isEqualTo(90);
        assertThat(other.get()).isZero();
        assertThat(reserved("TST-HOT")).isEqualTo(10);
        assertThat(onHand("TST-HOT")).isEqualTo(10);
    }
}