package com.ecommerce.notification.consumer;

import com.ecommerce.notification.model.NotificationType;
import com.ecommerce.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Event-driven integration: consumes platform domain events and turns them
 * into notifications.
 *
 * <p><strong>Disabled by default.</strong> The bean only exists when
 * {@code notifications.kafka.enabled=true} (see application.yml / k8s
 * configmap), so this service compiles and its tests pass with zero Kafka
 * infrastructure. In production, enable the flag and point
 * {@code spring.kafka.bootstrap-servers} at Azure Event Hubs — the
 * {@code KAFKA_BOOTSTRAP} env from ecommerce-env-config already carries the
 * SASL_SSL endpoint ({@code *.servicebus.windows.net:9093}); credentials
 * (SASL username/password) come from Azure Key Vault via external-secrets.
 */
@Component
@ConditionalOnProperty(name = "notifications.kafka.enabled", havingValue = "true")
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationService notifications;

    public NotificationConsumer(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * Handles order lifecycle events (order-svc / checkout-svc emit these on
     * the {@code order-events} topic in production).
     */
    @KafkaListener(topics = "order-events", groupId = "notification-svc")
    public void onOrderEvent(String payload) {
        log.info("order-events ← {}", payload);
        notifications.create(NotificationType.ORDER_CONFIRMED, "customer@example.com",
                "Order update", payload);
    }

    /**
     * Handles payment lifecycle events (payment-svc emits these on the
     * {@code payment-events} topic in production).
     */
    @KafkaListener(topics = "payment-events", groupId = "notification-svc")
    public void onPaymentEvent(String payload) {
        log.info("payment-events ← {}", payload);
        notifications.create(NotificationType.PAYMENT_RECEIVED, "customer@example.com",
                "Payment update", payload);
    }
}