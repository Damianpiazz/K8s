package com.ecommerce.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order management service.
 *
 * <p>Spring Data JPA on H2 for the demo profile — swap the datasource URL to
 * Azure-managed Postgres via the DB_HOST env (see k8s/base/configmap.yaml).
 * Config keys align with config-service {@code order-svc.yml}
 * ({@code order.max-items-per-order}).
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}