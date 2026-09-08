package com.ecommerce.bff.model;

import java.math.BigDecimal;

/**
 * A product as exposed to the storefront (catalog-svc projection).
 */
public record ProductSummary(long id, String name, String description,
                             BigDecimal price, String category) {
}