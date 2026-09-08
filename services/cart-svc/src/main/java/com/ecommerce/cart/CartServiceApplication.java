package com.ecommerce.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Shopping cart service.
 *
 * <p>Per-session carts on port 8082. The store is an in-memory map for the
 * demo; swap in Redis (Azure Cache for Redis, host from REDIS_HOST) in
 * production — see CartService and the commented dependency in pom.xml.
 */
@SpringBootApplication
public class CartServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}