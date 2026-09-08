package com.ecommerce.recommendation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Product recommendation service.
 *
 * <p>In-memory collaborative-ish scoring seeded from a small by-customer
 * purchase map. Production design (offline ML → Redis cache) is documented in
 * the README; the REST contract stays identical.
 */
@SpringBootApplication
public class RecommendationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecommendationServiceApplication.class, args);
    }
}