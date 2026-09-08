package com.ecommerce.shipping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Shipping service.
 *
 * <p>In-memory store for demo simplicity (cart-svc style). Status transitions
 * are manual and deterministic — no scheduled simulation. Production would
 * back this with a database and integrate with a real carrier API; see the
 * README.
 */
@SpringBootApplication
public class ShippingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShippingServiceApplication.class, args);
    }
}