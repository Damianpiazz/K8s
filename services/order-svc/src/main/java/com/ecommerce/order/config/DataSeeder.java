package com.ecommerce.order.config;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a couple of sample orders on startup so the API is demo-able
 * immediately (only when the table is empty).
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seedOrders(OrderRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<Order> seeds = List.of(
                    new Order("cust-42", List.of(
                            new OrderItem(1, 2, new BigDecimal("29.99")),
                            new OrderItem(2, 1, new BigDecimal("89.50"))),
                            new BigDecimal("149.48")),
                    new Order("cust-7", List.of(
                            new OrderItem(3, 1, new BigDecimal("249.00"))),
                            new BigDecimal("249.00"))
            );
            repository.saveAll(seeds);
            log.info("Seeded {} sample orders", seeds.size());
        };
    }
}