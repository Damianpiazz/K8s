package com.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Cloud Gateway — the single HTTP entrypoint for the platform.
 *
 * <p>Routes (declared in application.yml) forward /api/** paths to backend
 * services via lb:// URIs resolved through Eureka (discovery locator enabled;
 * see discovery-service). TLS termination happens at the cluster Ingress.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}