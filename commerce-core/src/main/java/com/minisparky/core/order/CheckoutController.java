package com.minisparky.core.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minisparky.core.audit.AuditService;
import com.minisparky.core.auth.Caller;
import com.minisparky.core.cart.CartService;
import com.minisparky.core.error.ApiException;

@RestController
@RequestMapping("/api/carts/{id}")
public class CheckoutController {

    private final CheckoutService checkout;
    private final CartService carts;
    private final AuditService audit;

    public CheckoutController(CheckoutService checkout, CartService carts, AuditService audit) {
        this.checkout = checkout;
        this.carts = carts;
        this.audit = audit;
    }

    @PostMapping("/checkout")
    public CheckoutService.OrderResponse checkout(@RequestAttribute("caller") Caller caller,
                                                  @PathVariable String id,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required");
        }
        return audit.run(caller, "CHECKOUT", id, null, null, () -> checkout.checkout(caller, id, key));
    }

    @PostMapping("/cancel")
    public CartService.CartStatus cancel(@RequestAttribute("caller") Caller caller, @PathVariable String id) {
        return audit.run(caller, "CANCEL", id, null, null, () -> carts.cancel(caller, id));
    }
}