package com.ecommerce.analytics.model;

/**
 * Aggregated product popularity — one row of the top-products report.
 *
 * @param productId product id
 * @param count     number of events referencing that product
 */
public record TopProduct(long productId, long count) {
}