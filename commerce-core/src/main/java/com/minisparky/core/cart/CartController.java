package com.minisparky.core.cart;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minisparky.core.audit.AuditService;
import com.minisparky.core.auth.Caller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/carts")
public class CartController {

    public record CreateCartRequest(@Min(1) Integer budgetPaise) {}

    public record AddItemRequest(@NotBlank @Size(max = 40) String sku, @Min(1) @Max(1000) int qty) {}

    private final CartService carts;
    private final AuditService audit;

    public CartController(CartService carts, AuditService audit) {
        this.carts = carts;
        this.audit = audit;
    }

    @PostMapping
    public CartService.CartCreated create(@RequestAttribute("caller") Caller caller,
                                          @Valid @RequestBody(required = false) CreateCartRequest body) {
        Integer budget = body == null ? null : body.budgetPaise();
        return audit.run(caller, "CREATE_CART", null, null, null, () -> carts.createCart(caller, budget));
    }

    @PostMapping("/{id}/items")
    public CartService.CartView add(@RequestAttribute("caller") Caller caller, @PathVariable String id,
                                    @Valid @RequestBody AddItemRequest body) {
        return audit.run(caller, "ADD_ITEM", id, body.sku(), body.qty(),
                () -> carts.addItem(caller, id, body.sku(), body.qty()));
    }

    @DeleteMapping("/{id}/items/{sku}")
    public CartService.CartView remove(@RequestAttribute("caller") Caller caller,
                                       @PathVariable String id, @PathVariable String sku) {
        return audit.run(caller, "REMOVE_ITEM", id, sku, null, () -> carts.removeItem(caller, id, sku));
    }

    @GetMapping("/{id}")
    public CartService.CartView get(@RequestAttribute("caller") Caller caller, @PathVariable String id) {
        return carts.getCart(caller, id);
    }
}