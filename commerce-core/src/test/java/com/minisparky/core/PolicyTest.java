package com.minisparky.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.minisparky.core.auth.Caller;

class PolicyTest extends AbstractIntegrationTest {

    private Resp addOverHttp(String sid, String cartId, String sku, int qty) {
        return call("POST", "/api/carts/" + cartId + "/items",
                "{\"sku\":\"" + sku + "\",\"qty\":" + qty + "}",
                "X-Agent-Key", AGENT_KEY, "X-Session-Id", sid);
    }

    @Test
    void quantityOverLimitIsBlocked() {
        seedProduct("TST-A", 1000, 5, 100);
        String sid = newSession();
        String cartId = carts.createCart(agent(sid), null).cartId();

        Resp r = addOverHttp(sid, cartId, "TST-A", 6);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).contains("QTY_LIMIT");
        assertThat(reserved("TST-A")).isZero();
        assertThat(blockedCount(sid, "QTY_LIMIT")).isEqualTo(1);
    }

    @Test
    void budgetExceededIsBlocked() {
        seedProduct("TST-A", 3000, 10, 100);
        String sid = newSession();
        String cartId = carts.createCart(agent(sid), 5000).cartId();

        Resp r = addOverHttp(sid, cartId, "TST-A", 2);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).contains("BUDGET_EXCEEDED");
        assertThat(reserved("TST-A")).isZero();
        assertThat(blockedCount(sid, "BUDGET_EXCEEDED")).isEqualTo(1);
    }

    @Test
    void twentySixthLineIsBlocked() {
        String sid = newSession();
        Caller agent = agent(sid);
        String cartId = carts.createCart(agent, null).cartId();
        for (int i = 1; i <= 26; i++) {
            seedProduct(String.format("TST-L%02d", i), 100, 10, 10);
        }
        for (int i = 1; i <= 25; i++) {
            carts.addItem(agent, cartId, String.format("TST-L%02d", i), 1);
        }

        Resp r = addOverHttp(sid, cartId, "TST-L26", 1);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).contains("LINE_LIMIT");
        assertThat(reserved("TST-L26")).isZero();
        assertThat(blockedCount(sid, "LINE_LIMIT")).isEqualTo(1);
    }
}