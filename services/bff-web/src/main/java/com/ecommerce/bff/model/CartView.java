package com.ecommerce.bff.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cart view: the cart from cart-svc joined with product details from
 * catalog-svc.
 *
 * @param cartId   the session cart id
 * @param items    product-joined lines
 * @param total    the cart monetary total
 * @param degraded true when a backend call failed (details may be partial)
 */
public record CartView(String cartId, List<CartItemView> items,
                       BigDecimal total, boolean degraded) {

    public static CartView degraded(String cartId) {
        return new CartView(cartId, List.of(), BigDecimal.ZERO, true);
    }
}