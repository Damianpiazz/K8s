package com.ecommerce.cart.model;

import java.math.BigDecimal;

/**
 * One line of a cart: a product reference and its quantity.
 *
 * <p>{@code unitPrice} is captured when the item is added. In a real system the
 * price would be fetched from catalog-svc (WebClient/Feign) — the client-driven
 * value keeps this service decoupled until that integration exists.
 */
public record CartItem(long productId, int quantity, BigDecimal unitPrice) {

    /** Line total: quantity × unit price (null price treated as zero). */
    public BigDecimal lineTotal() {
        BigDecimal price = unitPrice != null ? unitPrice : BigDecimal.ZERO;
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}