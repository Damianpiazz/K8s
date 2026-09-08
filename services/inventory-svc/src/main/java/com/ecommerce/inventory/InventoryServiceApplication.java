package com.ecommerce.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory management service.
 *
 * <p>Spring Data JPA on H2 for the demo profile — swap the datasource URL to
 * Azure-managed Postgres via the DB_HOST env (see k8s/base/configmap.yaml).
 * Reservations live in an in-memory map on top of the persisted stock levels;
 * config keys align with config-service {@code inventory-svc.yml}
 * ({@code inventory.reservation-ttl-minutes}).
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}