package com.minisparky.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.minisparky.core.auth.Caller;
import com.minisparky.core.auth.SessionService;

class AuthTest extends AbstractIntegrationTest {

    @Autowired SessionService sessions;

    @Test
    void agentKeyGets403OnUserOnlyEndpoints() {
        seedProduct("TST-A", 1000, 10, 10);
        String sid = newSession();
        String cartId = carts.createCart(agent(sid), null).cartId();
        carts.addItem(agent(sid), cartId, "TST-A", 1);

        Resp checkout = call("POST", "/api/carts/" + cartId + "/checkout", null,
                "X-Agent-Key", AGENT_KEY, "X-Session-Id", sid, "Idempotency-Key", "k1");
        Resp cancel = call("POST", "/api/carts/" + cartId + "/cancel", null,
                "X-Agent-Key", AGENT_KEY, "X-Session-Id", sid);
        Resp audit = call("GET", "/api/audit?sessionId=" + sid, null,
                "X-Agent-Key", AGENT_KEY, "X-Session-Id", sid);

        assertThat(checkout.status()).isEqualTo(403);
        assertThat(cancel.status()).isEqualTo(403);
        assertThat(audit.status()).isEqualTo(403);
        assertThat(count("orders")).isZero();
        assertThat(blockedCount(sid, "FORBIDDEN")).isEqualTo(3);
    }

    @Test
    void missingOrWrongCredentialsGet401() {
        assertThat(call("GET", "/api/products/search?q=chips", null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/products/search?q=chips", null,
                "X-Agent-Key", "wrong", "X-Session-Id", "s").status()).isEqualTo(401);
        assertThat(call("GET", "/api/products/search?q=chips", null,
                "X-User-Token", "forged.token").status()).isEqualTo(401);
    }

    @Test
    void userTokenCanCheckoutAndKeyIsRequired() {
        seedProduct("TST-A", 2000, 10, 10);
        SessionService.Session s = sessions.issue();
        Caller user = new Caller("user", s.sessionId());
        String cartId = carts.createCart(user, null).cartId();
        carts.addItem(user, cartId, "TST-A", 1);

        Resp noKey = call("POST", "/api/carts/" + cartId + "/checkout", null, "X-User-Token", s.userToken());
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body()).contains("MISSING_IDEMPOTENCY_KEY");

        Resp ok = call("POST", "/api/carts/" + cartId + "/checkout", null,
                "X-User-Token", s.userToken(), "Idempotency-Key", "k1");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).contains("PLACED");
        assertThat(count("orders")).isEqualTo(1);
    }
}