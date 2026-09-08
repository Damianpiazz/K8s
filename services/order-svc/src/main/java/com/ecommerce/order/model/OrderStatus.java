package com.ecommerce.order.model;

/**
 * Lifecycle of an order.
 */
public enum OrderStatus {
    CREATED,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED
}