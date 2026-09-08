package com.ecommerce.inventory.service;

import com.ecommerce.inventory.model.InventoryItem;
import com.ecommerce.inventory.model.Reservation;
import com.ecommerce.inventory.model.ReservationStatus;
import com.ecommerce.inventory.repository.InventoryItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Inventory business rules: stock CRUD plus reservation lifecycle
 * (reserve → release/confirm).
 *
 * <p>Stock rows are persisted (JPA/H2); reservations are an in-memory map on
 * top — sufficient for the demo, but a production reservation must be persisted
 * too so it survives restarts and can be expired by a sweeper job.
 *
 * <p>Per-instance state: at 2 replicas each pod keeps its own reservation map
 * and its own H2 copy. Production single-writer semantics (Postgres row locks)
 * are noted in the README.
 */
@Service
public class InventoryService {

    private final InventoryItemRepository items;
    private final long reservationTtlMinutes;
    private final ConcurrentHashMap<Long, Reservation> reservations = new ConcurrentHashMap<>();
    private final AtomicLong reservationIds = new AtomicLong(1);

    public InventoryService(InventoryItemRepository items,
                            @Value("${inventory.reservation-ttl-minutes:15}") long reservationTtlMinutes) {
        this.items = items;
        this.reservationTtlMinutes = reservationTtlMinutes;
    }

    public List<InventoryItem> findAll() {
        return items.findAll();
    }

    public Optional<InventoryItem> findByProductId(long productId) {
        return items.findByProductId(productId);
    }

    /** Replaces the on-hand stock level (existing reservations are untouched). */
    public InventoryItem updateStock(long productId, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
        InventoryItem item = items.findByProductId(productId)
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        item.setStockLevel(quantity);
        return items.save(item);
    }

    /**
     * Reserves {@code quantity} units if available; otherwise {@link
     * InsufficientStockException} (HTTP 409).
     */
    public synchronized Reservation reserve(long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        InventoryItem item = items.findByProductId(productId)
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        int available = item.getStockLevel() - item.getReserved();
        if (available < quantity) {
            throw new InsufficientStockException(
                    "Only " + available + " units available for product " + productId);
        }
        long id = reservationIds.getAndIncrement();
        Instant now = Instant.now();
        Reservation reservation = new Reservation(id, productId, quantity, now,
                now.plus(reservationTtlMinutes, ChronoUnit.MINUTES));
        reservations.put(id, reservation);
        item.setReserved(item.getReserved() + quantity);
        items.save(item);
        return reservation;
    }

    /** Releases a RESERVED reservation back to available stock. */
    public synchronized Reservation release(long reservationId) {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null) {
            throw new NoSuchElementException("Reservation " + reservationId + " not found");
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "Reservation " + reservationId + " is " + reservation.getStatus());
        }
        reservation.setStatus(ReservationStatus.RELEASED);
        InventoryItem item = items.findByProductId(reservation.getProductId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Product " + reservation.getProductId() + " not found"));
        item.setReserved(Math.max(0, item.getReserved() - reservation.getQuantity()));
        items.save(item);
        return reservation;
    }

    /** Commits a RESERVED reservation to the order (stock stays held). */
    public synchronized Reservation confirm(long reservationId) {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null) {
            throw new NoSuchElementException("Reservation " + reservationId + " not found");
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "Reservation " + reservationId + " is " + reservation.getStatus());
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        return reservation;
    }
}