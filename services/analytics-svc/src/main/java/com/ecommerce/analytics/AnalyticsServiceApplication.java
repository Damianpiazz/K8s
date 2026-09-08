package com.ecommerce.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Analytics event ingestion & report service.
 *
 * <p>Demonstrates the write-then-query pattern with an in-memory event log
 * (bounded ConcurrentLinkedQueue) plus aggregated counters. Production design
 * (Azure Event Hubs + aggregator) is documented in the README; the REST
 * contract stays identical.
 */
@SpringBootApplication
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}