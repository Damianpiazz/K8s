package com.ecommerce.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification service.
 *
 * <p>Main path: a plain REST API that stores notifications in-memory.
 * Event-driven path: {@code NotificationConsumer} listens to the
 * {@code order-events}/{@code payment-events} Kafka topics (Azure Event Hubs
 * in production) — gated by {@code notifications.kafka.enabled=true} so the
 * app compiles and tests without any broker.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}