package com.ecommerce.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Product catalog service.
 *
 * <p>Exposes the product REST API on port 8081, registers with Eureka and
 * (optionally) pulls `catalog-svc.yml` from the config server. Persistence is
 * Spring Data JPA on H2 for the demo profile — swap the datasource URL to
 * Azure-managed Postgres via the DB_HOST env (see k8s/base/configmap.yaml).
 */
@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}