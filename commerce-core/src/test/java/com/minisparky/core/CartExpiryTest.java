package com.minisparky.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.cart.CartExpiryJob;

class CartExpiryTest extends AbstractIntegrationTest {

    @Autowired CartExpiryJob expiryJob;

    @Test
    void expiredCartReleasesItsStockExactlyOnce() {
        seedProduct("TST-A", 1000, 10, 5);
        Caller agent = agent(newSession());
        String cartId = carts.createCart(agent, null).cartId();
        carts.addItem(agent, cartId, "TST-A", 3);
        assertThat(reserved("TST-A")).isEqualTo(3);

        jdbc.update("UPDATE carts SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", cartId);

        assertThat(expiryJob.expireDue()).isEqualTo(1);
        assertThat(reserved("TST-A")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM carts WHERE id = ?::uuid", String.class, cartId))
                .isEqualTo("EXPIRED");

        assertThat(expiryJob.expireDue()).isZero();
        assertThat(reserved("TST-A")).isZero();
    }
}