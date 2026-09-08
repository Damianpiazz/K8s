package com.ecommerce.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend-for-frontend for the web storefront.
 *
 * <p>Aggregates catalog-svc (:8081) and cart-svc (:8082) into storefront views
 * using non-blocking WebClient calls (2s timeout). When a backend is down the
 * BFF degrades gracefully ({@code degraded: true}) instead of failing the page.
 */
@SpringBootApplication
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}