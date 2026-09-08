package com.ecommerce.shipping.service;

import com.ecommerce.shipping.model.ShippingOrder;
import com.ecommerce.shipping.model.ShippingOrder.Address;
import com.ecommerce.shipping.model.ShippingStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory shipping store keyed by shipment id.
 *
 * <p>Status transitions are manual and deterministic (no simulation): the API
 * only allows {@code PENDING → SHIPPED → DELIVERED} (and {@code PENDING →
 * DELIVERED} for demo convenience). Moving to {@code SHIPPED} generates the
 * tracking number. A production implementation persists shipments and calls a
 * real carrier API — see the README.
 */
@Service
public class ShippingService {

    private final ConcurrentHashMap<Long, ShippingOrder> shipments = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final String defaultCarrier;
    private final String trackingPrefix;

    public ShippingService(@Value("${shipping.default-carrier:Ecommerce Express}") String defaultCarrier,
                           @Value("${shipping.tracking-prefix:EC}") String trackingPrefix) {
        this.defaultCarrier = defaultCarrier;
        this.trackingPrefix = trackingPrefix;
    }

    /** Creates a shipment in PENDING state. */
    public ShippingOrder create(String orderId, Address address) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (address == null || address.city() == null || address.city().isBlank()) {
            throw new IllegalArgumentException("address with a city is required");
        }
        long id = ids.getAndIncrement();
        ShippingOrder order = new ShippingOrder(id, orderId, address,
                ShippingStatus.PENDING, defaultCarrier, Instant.now());
        shipments.put(id, order);
        return order;
    }

    public Optional<ShippingOrder> findById(long id) {
        return Optional.ofNullable(shipments.get(id));
    }

    /** All shipments of one order (an order may ship in multiple batches). */
    public List<ShippingOrder> findByOrderId(String orderId) {
        return shipments.values().stream()
                .filter(s -> s.getOrderId().equals(orderId))
                .sorted(java.util.Comparator.comparingLong(ShippingOrder::getId))
                .toList();
    }

    /**
     * Transition a shipment. Allowed targets: {@code SHIPPED}, {@code DELIVERED}.
     * Terminal shipments (DELIVERED/FAILED) refuse further transitions.
     */
    public ShippingOrder updateStatus(long id, String status) {
        ShippingStatus target;
        try {
            target = ShippingStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown shipping status: " + status);
        }
        if (target != ShippingStatus.SHIPPED && target != ShippingStatus.DELIVERED) {
            throw new IllegalArgumentException(
                    "Status transition to " + target + " not allowed (use SHIPPED or DELIVERED)");
        }
        ShippingOrder order = shipments.get(id);
        if (order == null) {
            throw new NoSuchElementException("Shipment " + id + " not found");
        }
        if (order.getStatus() == ShippingStatus.DELIVERED
                || order.getStatus() == ShippingStatus.FAILED) {
            throw new IllegalStateException(
                    "Shipment " + id + " is terminal (" + order.getStatus() + ")");
        }
        order.setStatus(target);
        if (target == ShippingStatus.SHIPPED && order.getTrackingNumber() == null) {
            order.setTrackingNumber(trackingPrefix + String.format("%08d", order.getId()));
        }
        return order;
    }
}