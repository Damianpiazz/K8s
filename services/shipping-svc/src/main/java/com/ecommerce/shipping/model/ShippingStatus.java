package com.ecommerce.shipping.model;

/**
 * Lifecycle of a shipment.
 */
public enum ShippingStatus {
    /** Created, awaiting dispatch. */
    PENDING,
    /** Handed to the carrier — a tracking number is assigned. */
    SHIPPED,
    /** Delivered to the customer. */
    DELIVERED,
    /** Delivery failed (set externally by the carrier integration). */
    FAILED
}