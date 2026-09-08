package com.ecommerce.inventory.service;

/**
 * Thrown when a reserve request exceeds the available stock
 * ({@code stockLevel - reserved}). Mapped to HTTP 409 Conflict.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}