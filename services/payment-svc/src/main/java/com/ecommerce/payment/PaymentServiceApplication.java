package com.ecommerce.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment processing service.
 *
 * <p>Store is an in-memory map for the demo (see PaymentService); the JPA/H2
 * stack is on the classpath and datasource keys (application.yml ↔
 * config-service payment-svc.yml) are ready for the Azure Postgres path.
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}