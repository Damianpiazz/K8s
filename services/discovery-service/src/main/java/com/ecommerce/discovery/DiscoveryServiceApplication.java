package com.ecommerce.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka server — the service registry.
 *
 * <p>Every Spring Cloud service registers here and resolves peers through it.
 * Two instances (or more) provide HA in production; a single replica is fine
 * for the university project (see README + prod overlay).
 */
@EnableEurekaServer
@SpringBootApplication
public class DiscoveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
}