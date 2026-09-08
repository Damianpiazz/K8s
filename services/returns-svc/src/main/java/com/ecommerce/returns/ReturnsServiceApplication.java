package com.ecommerce.returns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Returns (RMA) service.
 *
 * <p>In-memory store with a visible auto-approval rule: a request is approved
 * automatically when the reason is on the allow-list, it arrives within the
 * return window, and the quantity fits the auto-approve cap; everything else
 * goes to PENDING_REVIEW. Config keys align with config-service
 * {@code returns-svc.yml}.
 */
@SpringBootApplication
public class ReturnsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReturnsServiceApplication.class, args);
    }
}