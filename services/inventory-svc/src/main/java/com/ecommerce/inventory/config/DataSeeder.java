package com.ecommerce.inventory.config;

import com.ecommerce.inventory.model.InventoryItem;
import com.ecommerce.inventory.repository.InventoryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Seeds 8 SKUs on startup so the API is demo-able immediately. Product ids
 * 1-8 mirror the search/recommendation demo catalogs.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seedInventory(InventoryItemRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<InventoryItem> seeds = List.of(
                    new InventoryItem(1L, "SKU-MX10", "Wireless Mouse MX-10", 120),
                    new InventoryItem(2L, "SKU-K87", "Mechanical Keyboard K87", 45),
                    new InventoryItem(3L, "SKU-M27", "27\" IPS Monitor", 18),
                    new InventoryItem(4L, "SKU-DOCK", "USB-C Docking Station", 30),
                    new InventoryItem(5L, "SKU-STND", "Laptop Stand Pro", 60),
                    new InventoryItem(6L, "SKU-WEB", "Webcam HD 1080p", 75),
                    new InventoryItem(7L, "SKU-HS", "Bluetooth Headset NC", 40),
                    new InventoryItem(8L, "SKU-SW2", "Smartwatch S2", 25)
            );
            repository.saveAll(seeds);
            log.info("Seeded {} inventory SKUs", seeds.size());
        };
    }
}