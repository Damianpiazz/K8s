package com.ecommerce.payment.controller;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payment REST API. Mounted under /api/payments so the api-gateway forwards
 * /api/payments/** untouched.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * Request body: {"orderId": 1, "amount": 149.48, "method": "card",
     * "cardLast4": "4242"}.
     *
     * <p>{@code cardLast4} is a placeholder for the PSP token in a real
     * system — the business rule is: last-4 ending in 0000 → DECLINED.
     */
    public record PaymentRequest(long orderId, BigDecimal amount, String method,
                                 String cardLast4) {
    }

    /** Processes a payment attempt; returns the payment with APPROVED/DECLINED. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Payment process(@RequestBody PaymentRequest request) {
        return service.process(request.orderId(), request.amount(),
                request.method(), request.cardLast4());
    }

    /** Fetch a single payment; 404 if unknown. */
    @GetMapping("/{id}")
    public Payment get(@PathVariable long id) {
        return service.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Payment " + id + " not found"));
    }

    /** All payments for one order. */
    @GetMapping("/order/{orderId}")
    public List<Payment> byOrder(@PathVariable long orderId) {
        return service.findByOrder(orderId);
    }

    /** List all payments (demo store visibility). */
    @GetMapping
    public List<Payment> list() {
        return service.findAll();
    }
}