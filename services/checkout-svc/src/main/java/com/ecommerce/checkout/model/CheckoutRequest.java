package com.ecommerce.checkout.model;

import java.math.BigDecimal;

/**
 * Inbound checkout payload.
 *
 * @param cartId        the shopping cart to check out (from cart-svc)
 * @param customerId    the customer placing the order
 * @param paymentMethod e.g. "card", "paypal", "invoice"
 */
public record CheckoutRequest(String cartId, String customerId, String paymentMethod) {
}