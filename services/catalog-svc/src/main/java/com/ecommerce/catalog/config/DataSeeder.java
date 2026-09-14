package com.ecommerce.catalog.config;

import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a handful of products on startup so the API is demo-able immediately.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seedProducts(ProductRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<Product> seeds = List.of(
                    new Product("Mouse Inalámbrico MX-10", "Mouse inalámbrico ergonómico de 2,4 GHz",
                            new BigDecimal("29.99"), 120, "Accesorios"),
                    new Product("Teclado Mecánico K87", "Hot-swappable, RGB, switches azules",
                            new BigDecimal("89.50"), 45, "Accesorios"),
                    new Product("Monitor IPS 27\"", "2560x1440, 75 Hz, calibrado de fábrica",
                            new BigDecimal("249.00"), 18, "Pantallas"),
                    new Product("Estación de Acoplamiento USB-C", "Doble HDMI, 100 W PD, 10 Gbps",
                            new BigDecimal("139.90"), 30, "Accesorios")
            );
            repository.saveAll(seeds);
            log.info("Seeded {} demo products", seeds.size());
        };
    }
}