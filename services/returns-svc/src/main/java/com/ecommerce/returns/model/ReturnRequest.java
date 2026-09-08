package com.ecommerce.returns.model;

import java.time.Instant;

/**
 * A return request (RMA) for one product line of an order.
 */
public class ReturnRequest {

    private final long id;
    private final String orderId;
    private final long productId;
    private final String reason;
    private final int quantity;
    private final Instant createdAt;
    private volatile ReturnStatus status;

    public ReturnRequest(long id, String orderId, long productId, String reason,
                         int quantity, Instant createdAt, ReturnStatus status) {
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.reason = reason;
        this.quantity = quantity;
        this.createdAt = createdAt;
        this.status = status;
    }

    public long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getProductId() {
        return productId;
    }

    public String getReason() {
        return reason;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public void setStatus(ReturnStatus status) {
        this.status = status;
    }
}