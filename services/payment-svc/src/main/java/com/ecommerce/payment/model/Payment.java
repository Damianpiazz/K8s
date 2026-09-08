package com.ecommerce.payment.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A recorded payment attempt.
 *
 * <p>Demo store: in-memory map keyed by incremental id (see PaymentService).
 * Production path: JPA entity mirroring this shape over Azure Postgres
 * ({@code SPRING_DATASOURCE_URL} override in k8s/base/configmap.yaml).
 */
public class Payment {

    private final long id;
    private final long orderId;
    private final BigDecimal amount;
    private final String method;
    private final PaymentStatus status;
    private final Instant createdAt;

    public Payment(long id, long orderId, BigDecimal amount, String method, PaymentStatus status) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}