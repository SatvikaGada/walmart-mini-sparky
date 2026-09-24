package com.minisparky.core.policy;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.minisparky.core.config.AppProperties;
import com.minisparky.core.error.ApiException;

@Component
public class PolicyGuard {

    public static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);

    private final AppProperties props;

    public PolicyGuard(AppProperties props) {
        this.props = props;
    }

    public void requireOpen(String status, boolean expired) {
        if (!"OPEN".equals(status)) {
            throw new ApiException(UNPROCESSABLE, "CART_NOT_OPEN", "Cart is " + status);
        }
        if (expired) {
            throw new ApiException(UNPROCESSABLE, "CART_EXPIRED", "Cart has expired");
        }
    }

    public long effectiveLimit(Integer budgetPaise) {
        long cap = props.maxCartTotalPaise();
        return budgetPaise == null ? cap : Math.min(budgetPaise, cap);
    }

    public void checkAdd(int newQty, int maxQtyPerOrder, boolean newLine, int currentLines,
                         long newTotalPaise, Integer budgetPaise) {
        if (newQty > maxQtyPerOrder) {
            throw new ApiException(UNPROCESSABLE, "QTY_LIMIT", "Quantity exceeds the per-order limit",
                    Map.of("maxQtyPerOrder", maxQtyPerOrder, "requested", newQty));
        }
        if (newLine && currentLines + 1 > props.maxLinesPerCart()) {
            throw new ApiException(UNPROCESSABLE, "LINE_LIMIT", "Too many distinct lines in the cart",
                    Map.of("maxLines", props.maxLinesPerCart()));
        }
        checkTotal(newTotalPaise, budgetPaise);
    }

    public void checkTotal(long totalPaise, Integer budgetPaise) {
        long limit = effectiveLimit(budgetPaise);
        if (totalPaise > limit) {
            throw new ApiException(UNPROCESSABLE, "BUDGET_EXCEEDED", "Cart total would exceed the budget",
                    Map.of("limitPaise", limit, "totalPaise", totalPaise));
        }
    }
}