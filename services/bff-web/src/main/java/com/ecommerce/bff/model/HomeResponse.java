package com.ecommerce.bff.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Storefront homepage payload: the product grid plus a featured/hero product.
 *
 * @param products the product grid (empty when catalog-svc is unreachable)
 * @param hero     the featured product (null when catalog-svc is unreachable)
 * @param degraded true when one or more backend calls failed
 */
public record HomeResponse(List<ProductSummary> products, ProductSummary hero, boolean degraded) {

    public static HomeResponse degraded() {
        return new HomeResponse(List.of(), null, true);
    }
}