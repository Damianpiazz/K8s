package com.ecommerce.checkout.service;

import com.ecommerce.checkout.model.CheckoutRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Event-driven integration: emits checkout domain events onto the
 * {@code order-events} and {@code payment-events} topics.
 *
 * <p><strong>Disabled by default.</strong> The bean only exists when
 * {@code checkout.events.kafka.enabled=true} (see application.yml / k8s
 * env), so the service compiles and its tests pass with zero Kafka
 * infrastructure. In production, enable the flag and point
 * {@code spring.kafka.bootstrap-servers} at Azure Event Hubs — the
 * {@code KAFKA_BOOTSTRAP} env from ecommerce-env-config already carries the
 * SASL_SSL endpoint; credentials come from Azure Key Vault via external-secrets.
 *
 * <p>Producer failures never affect the checkout response: payload
 * construction, serialization and the send are all guarded, the send is
 * asynchronous ({@link KafkaTemplate#send(String, Object)} returns a
 * {@link java.util.concurrent.CompletableFuture} that is logged on
 * completion), and null fields are sanitized so a malformed checkout record
 * can never throw out of the publish path.
 */
@Component
@ConditionalOnProperty(name = "checkout.events.kafka.enabled", havingValue = "true")
public class CheckoutEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CheckoutEventPublisher.class);

    public static final String ORDER_EVENTS_TOPIC = "order-events";
    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public CheckoutEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /** Publishes the order lifecycle event after a completed checkout. */
    public void publishOrder(CheckoutRecord record) {
        send(ORDER_EVENTS_TOPIC, () -> Map.of(
                "orderId", record.getId(),
                "customerId", safe(record.getCustomerId()),
                "total", plainTotal(record),
                "status", record.getStatus()));
    }

    /** Publishes the payment lifecycle event after a completed checkout. */
    public void publishPayment(CheckoutRecord record) {
        send(PAYMENT_EVENTS_TOPIC, () -> Map.of(
                "orderId", record.getId(),
                "paymentMethod", safe(record.getPaymentMethod()),
                "amount", plainTotal(record),
                "status", "PAID"));
    }

    /** Null-safe map value: nulls become "" so {@code Map.of} never throws. */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Total as a plain string; guards a null total so {@code Map.of} never NPEs. */
    private String plainTotal(CheckoutRecord record) {
        BigDecimal total = record.getTotal();
        return total != null
                ? total.stripTrailingZeros().toPlainString()
                : "0";
    }

    /**
     * Builds the payload, serializes it and sends it asynchronously. Every
     * step is guarded: a failure here is logged and never propagates to the
     * caller, so a down broker or malformed record cannot break checkout.
     */
    private void send(String topic, Supplier<Map<String, Object>> payloadSupplier) {
        try {
            Map<String, Object> payload = payloadSupplier.get();
            String json = objectMapper.writeValueAsString(payload);
            kafka.send(topic, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish {} ({}); checkout continues",
                            topic, ex.getMessage());
                } else {
                    log.info("Published {} ← {}", topic, json);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to publish {} ({}); checkout continues", topic, e.getMessage());
        }
    }
}