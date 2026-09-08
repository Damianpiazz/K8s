package com.ecommerce.inventory.model;

import java.time.Instant;

/**
 * A reservation holds {@code quantity} units of {@code productId} for a limited
 * time (see {@code inventory.reservation-ttl-minutes}).
 *
 * <p>Kept in an in-memory map (see InventoryService) for the demo — a
 * production reservation would live in the database alongside the stock row so
 * it survives restarts and can be scanned for expiry. The REST contract stays
 * identical.
 */
public class Reservation {

    private final long id;
    private final long productId;
    private final int quantity;
    private final Instant createdAt;
    private final Instant expiresAt;
    private volatile ReservationStatus status;

    public Reservation(long id, long productId, int quantity,
                       Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.productId = productId;
        this.quantity = quantity;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.RESERVED;
    }

    public long getId() {
        return id;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }
}