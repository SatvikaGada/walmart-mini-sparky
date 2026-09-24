package com.minisparky.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.error.ApiException;
import com.minisparky.core.order.CheckoutService;
import com.minisparky.core.order.CheckoutService.OrderResponse;

class CheckoutTest extends AbstractIntegrationTest {

    @Autowired CheckoutService checkoutService;

    private String cartWith(Caller c, String sku, int qty) {
        String id = carts.createCart(c, null).cartId();
        carts.addItem(c, id, sku, qty);
        return id;
    }

    @Test
    void sameKeyTenTimesInParallelCreatesExactlyOneOrder() throws Exception {
        seedProduct("TST-A", 5000, 10, 20);
        Caller user = new Caller("user", newSession());
        String cartId = cartWith(user, "TST-A", 2);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OrderResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return checkoutService.checkout(user, cartId, "same-key");
            }));
        }
        go.countDown();
        Set<OrderResponse> distinct = new HashSet<>();
        for (Future<OrderResponse> f : futures) distinct.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(distinct).hasSize(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(onHand("TST-A")).isEqualTo(18);
        assertThat(reserved("TST-A")).isZero();
    }

    @Test
    void secondCheckoutWithDifferentKeyIsRejected() {
        seedProduct("TST-A", 5000, 10, 20);
        Caller user = new Caller("user", newSession());
        String cartId = cartWith(user, "TST-A", 1);

        checkoutService.checkout(user, cartId, "k1");

        assertThatThrownBy(() -> checkoutService.checkout(user, cartId, "k2"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CART_NOT_OPEN"));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    void sameKeyOnDifferentCartIsRejected() {
        seedProduct("TST-A", 1000, 10, 20);
        Caller user = new Caller("user", newSession());
        String cart1 = cartWith(user, "TST-A", 1);
        String cart2 = cartWith(user, "TST-A", 1);

        checkoutService.checkout(user, cart1, "k");

        assertThatThrownBy(() -> checkoutService.checkout(user, cart2, "k"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void failureOnThirdItemRollsBackEverything() {
        seedProduct("TST-A", 1000, 10, 10);
        seedProduct("TST-B", 1000, 10, 10);
        seedProduct("TST-C", 1000, 10, 10);
        Caller user = new Caller("user", newSession());
        String cartId = carts.createCart(user, null).cartId();
        carts.addItem(user, cartId, "TST-A", 1);
        carts.addItem(user, cartId, "TST-B", 2);
        carts.addItem(user, cartId, "TST-C", 3);

        // sabotage the third line so its COMMIT statement matches zero rows
        jdbc.update("UPDATE stock SET reserved = 0 WHERE sku = 'TST-C'");

        assertThatThrownBy(() -> checkoutService.checkout(user, cartId, "k"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STOCK_CONFLICT"));

        assertThat(onHand("TST-A")).isEqualTo(10);
        assertThat(reserved("TST-A")).isEqualTo(1);
        assertThat(onHand("TST-B")).isEqualTo(10);
        assertThat(reserved("TST-B")).isEqualTo(2);
        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_keys")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM carts WHERE id = ?::uuid", String.class, cartId))
                .isEqualTo("OPEN");
    }
}