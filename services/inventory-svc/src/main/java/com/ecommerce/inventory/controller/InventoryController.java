package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.model.InventoryItem;
import com.ecommerce.inventory.model.Reservation;
import com.ecommerce.inventory.service.InsufficientStockException;
import com.ecommerce.inventory.service.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Inventory REST API. Mounted under /api/inventory so the api-gateway forwards
 * /api/inventory/** untouched.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** Request body: {"quantity": 80}. */
    public record QuantityRequest(int quantity) {
    }

    /** Request body: {"productId": 1, "quantity": 3}. */
    public record ReserveRequest(long productId, int quantity) {
    }

    /** Request body: {"reservationId": 12}. */
    public record ReleaseRequest(long reservationId) {
    }

    /** All inventory rows. */
    @GetMapping
    public List<InventoryItem> all() {
        return inventory.findAll();
    }

    /** Single item by product id; 404 if unknown. */
    @GetMapping("/{productId}")
    public InventoryItem byProductId(@PathVariable long productId) {
        return inventory.findByProductId(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Product " + productId + " not found"));
    }

    /** Sets the on-hand stock level (reserved count untouched). */
    @PutMapping("/{productId}")
    public InventoryItem updateStock(@PathVariable long productId,
                                     @RequestBody QuantityRequest request) {
        try {
            return inventory.updateStock(productId, request.quantity());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Reserves units; 409 when the available stock is insufficient.
     * Body: {"productId": 1, "quantity": 3}.
     */
    @PostMapping("/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public Reservation reserve(@RequestBody ReserveRequest request) {
        try {
            return inventory.reserve(request.productId(), request.quantity());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (InsufficientStockException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Releases a reserved quantity back to stock. Body: {"reservationId": 12}. */
    @PostMapping("/release")
    public Reservation release(@RequestBody ReleaseRequest request) {
        try {
            return inventory.release(request.reservationId());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Commits a reservation to the order (stock stays held). */
    @PostMapping("/confirm/{reservationId}")
    public Reservation confirm(@PathVariable long reservationId) {
        try {
            return inventory.confirm(reservationId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}