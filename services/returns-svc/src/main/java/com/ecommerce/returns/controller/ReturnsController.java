package com.ecommerce.returns.controller;

import com.ecommerce.returns.model.ReturnRequest;
import com.ecommerce.returns.service.ReturnsService;
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
import java.util.NoSuchElementException;

/**
 * Returns (RMA) REST API. Mounted under /api/returns so the api-gateway
 * forwards /api/returns/** untouched.
 */
@RestController
@RequestMapping("/api/returns")
public class ReturnsController {

    private final ReturnsService returns;

    public ReturnsController(ReturnsService returns) {
        this.returns = returns;
    }

    /** Request body: {"orderId": "12", "productId": 3, "reason": "defective", "quantity": 1}. */
    public record CreateRequest(String orderId, long productId, String reason, int quantity) {
    }

    /**
     * Creates an RMA. Auto-approved (APPROVED) when the reason is on the
     * allow-list, within the return window, and quantity ≤ cap; otherwise
     * PENDING_REVIEW.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnRequest create(@RequestBody CreateRequest request) {
        try {
            return returns.create(request.orderId(), request.productId(),
                    request.reason(), request.quantity());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Fetch a single RMA; 404 if unknown. */
    @GetMapping("/{id}")
    public ReturnRequest get(@PathVariable long id) {
        return returns.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Return " + id + " not found"));
    }

    /** All RMAs of one order (newest first). */
    @GetMapping("/order/{orderId}")
    public List<ReturnRequest> byOrder(@PathVariable String orderId) {
        return returns.findByOrderId(orderId);
    }

    /** Operator approve; 409 if already rejected. */
    @PostMapping("/{id}/approve")
    public ReturnRequest approve(@PathVariable long id) {
        try {
            return returns.approve(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Operator reject; 409 if already approved. */
    @PostMapping("/{id}/reject")
    public ReturnRequest reject(@PathVariable long id) {
        try {
            return returns.reject(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}