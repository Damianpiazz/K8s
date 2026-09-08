package com.ecommerce.checkout.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Stored checkout record.
 *
 * <p>Demo store: in-memory map keyed by incremental id (see CheckoutService).
 * Production path: persist to Azure Postgres (JPA entity mirroring this shape)
 * and/or emit an order-events Kafka message for downstream services.
 */
public class CheckoutRecord {

    private final long id;
    private final String cartId;
    private final String customerId;
    private final String paymentMethod;
    private final BigDecimal total;
    private final String status;
    private final Instant createdAt;

    public CheckoutRecord(long id, String cartId, String customerId,
                          String paymentMethod, BigDecimal total, String status) {
        this.id = id;
        this.cartId = cartId;
        this.customerId = customerId;
        this.paymentMethod = paymentMethod;
        this.total = total;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public long getId() {
        return id;
    }

    public String getCartId() {
        return cartId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}