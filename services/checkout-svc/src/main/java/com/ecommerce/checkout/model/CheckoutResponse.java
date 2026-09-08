package com.ecommerce.checkout.model;

import java.math.BigDecimal;

/**
 * Checkout result returned to the caller.
 *
 * @param orderId the order reference created by the flow
 * @param total   the monetary total of the checked-out cart
 * @param status  human-readable outcome ("COMPLETED", "FAILED")
 */
public record CheckoutResponse(long orderId, BigDecimal total, String status) {
}