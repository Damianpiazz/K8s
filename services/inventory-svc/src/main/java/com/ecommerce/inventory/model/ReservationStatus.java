package com.ecommerce.inventory.model;

/**
 * Lifecycle of a stock reservation.
 */
public enum ReservationStatus {
    /** Units held for a pending order. */
    RESERVED,
    /** Units released back to available stock. */
    RELEASED,
    /** Reservation committed to an order (stock stays held). */
    CONFIRMED
}