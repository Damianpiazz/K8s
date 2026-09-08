package com.ecommerce.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Checkout orchestration service.
 *
 * <p>Coordinates a purchase: reads the shopping cart from cart-svc, computes
 * the total and records an in-memory order reference. Production would instead
 * POST the order to order-svc (persisted) and trigger payment-svc — the call
 * site is marked in {@code CheckoutService}.
 */
@SpringBootApplication
public class CheckoutServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
    }
}