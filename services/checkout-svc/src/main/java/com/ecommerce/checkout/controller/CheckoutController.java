package com.ecommerce.checkout.controller;

import com.ecommerce.checkout.model.CheckoutRecord;
import com.ecommerce.checkout.model.CheckoutRequest;
import com.ecommerce.checkout.model.CheckoutResponse;
import com.ecommerce.checkout.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Checkout REST API. Mounted under /api/checkout so the api-gateway forwards
 * /api/checkout/** untouched.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final CheckoutService service;

    public CheckoutController(CheckoutService service) {
        this.service = service;
    }

    /**
     * Executes the checkout flow.
     *
     * <p>Request: {"cartId": "session-1", "customerId": "cust-42", "paymentMethod": "card"}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse checkout(@RequestBody CheckoutRequest request) {
        try {
            return service.checkout(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Fetch a single checkout record. */
    @GetMapping("/{id}")
    public CheckoutRecord get(@PathVariable long id) {
        return service.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Checkout " + id + " not found"));
    }

    /** List all checkout records (demo store visibility). */
    @GetMapping
    public List<CheckoutRecord> list() {
        return service.findAll();
    }
}