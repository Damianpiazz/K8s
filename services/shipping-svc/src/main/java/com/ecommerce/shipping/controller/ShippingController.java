package com.ecommerce.shipping.controller;

import com.ecommerce.shipping.model.ShippingOrder;
import com.ecommerce.shipping.model.ShippingOrder.Address;
import com.ecommerce.shipping.service.ShippingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Shipping REST API. Mounted under /api/shipping so the api-gateway forwards
 * /api/shipping/** untouched.
 */
@RestController
@RequestMapping("/api/shipping")
public class ShippingController {

    private final ShippingService shipping;

    public ShippingController(ShippingService shipping) {
        this.shipping = shipping;
    }

    /** Request body: {"orderId": "12", "address": {"line1": "...", "city": "…", "postalCode": "…", "country": "…"}}. */
    public record CreateRequest(String orderId, Address address) {
    }

    /** Request body: {"status": "SHIPPED"}. */
    public record StatusRequest(String status) {
    }

    /** Creates a shipment in PENDING state. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingOrder create(@RequestBody CreateRequest request) {
        try {
            return shipping.create(request.orderId(), request.address());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Fetch a shipment; 404 if unknown. */
    @GetMapping("/{id}")
    public ShippingOrder get(@PathVariable long id) {
        return shipping.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Shipment " + id + " not found"));
    }

    /** All shipments of one order (newest last). */
    @GetMapping("/order/{orderId}")
    public List<ShippingOrder> byOrder(@PathVariable String orderId) {
        return shipping.findByOrderId(orderId);
    }

    /**
     * Manual status transition (allowed: SHIPPED, DELIVERED). Moving to SHIPPED
     * assigns the tracking number.
     */
    @PatchMapping("/{id}/status")
    public ShippingOrder updateStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        try {
            return shipping.updateStatus(id, request.status());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}