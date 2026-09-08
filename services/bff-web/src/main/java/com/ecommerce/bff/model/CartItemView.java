package com.ecommerce.bff.model;

import java.math.BigDecimal;

/**
 * One cart line with the joined product info.
 *
 * @param productId  from cart-svc
 * @param name       from catalog-svc ("Product #<id>" when the lookup failed)
 * @param quantity   from cart-svc
 * @param unitPrice  from cart-svc
 * @param lineTotal  quantity × unitPrice
 */
public record CartItemView(long productId, String name, int quantity,
                           BigDecimal unitPrice, BigDecimal lineTotal) {

    public CartItemView {
        BigDecimal price = unitPrice != null ? unitPrice : BigDecimal.ZERO;
        lineTotal = price.multiply(BigDecimal.valueOf(quantity));
    }
}