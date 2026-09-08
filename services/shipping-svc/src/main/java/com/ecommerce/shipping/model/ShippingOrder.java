package com.ecommerce.shipping.model;

import java.time.Instant;

/**
 * A shipment for one order.
 *
 * <p>Address is a flat value object serialized inline. {@code trackingNumber}
 * is generated when the status moves to {@code SHIPPED}.
 */
public class ShippingOrder {

    private final long id;
    private final String orderId;
    private final Address address;
    private volatile ShippingStatus status;
    private final String carrier;
    private String trackingNumber;
    private final Instant createdAt;

    public ShippingOrder(long id, String orderId, Address address,
                         ShippingStatus status, String carrier, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.address = address;
        this.status = status;
        this.carrier = carrier;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public Address getAddress() {
        return address;
    }

    public ShippingStatus getStatus() {
        return status;
    }

    public void setStatus(ShippingStatus status) {
        this.status = status;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Flat delivery address. */
    public record Address(String line1, String city, String postalCode, String country) {
    }
}