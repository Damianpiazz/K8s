package com.ecommerce.checkout.service;

import com.ecommerce.checkout.model.CheckoutRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CheckoutEventPublisher} — no real Kafka, no sleeps.
 *
 * <p>The publisher is exception-safe by design: null record fields, a failed
 * send future and a throwing broker call are all logged, never propagated,
 * so checkout keeps working with a down or absent broker.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private KafkaTemplate<String, String> kafka;

    private CheckoutEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new CheckoutEventPublisher(kafka, objectMapper);
    }

    @Test
    void publishesOrderEventToOrderTopic() throws Exception {
        CheckoutRecord record = new CheckoutRecord(42L, "cart-42", "cust-42", "card",
                new BigDecimal("59.98"), "COMPLETED");

        publisher.publishOrder(record);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("order-events"), captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue());
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
        assertThat(json.get("customerId").asText()).isEqualTo("cust-42");
        assertThat(json.get("total").asText()).isEqualTo("59.98");
        assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void publishesPaymentEventToPaymentTopic() throws Exception {
        CheckoutRecord record = new CheckoutRecord(7L, "cart-7", "cust-7", "paypal",
                new BigDecimal("19.99"), "COMPLETED");

        publisher.publishPayment(record);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("payment-events"), captor.capture());

        JsonNode json = objectMapper.readTree(captor.getValue());
        assertThat(json.get("orderId").asLong()).isEqualTo(7L);
        assertThat(json.get("paymentMethod").asText()).isEqualTo("paypal");
        assertThat(json.get("amount").asText()).isEqualTo("19.99");
        assertThat(json.get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void nullsDoNotThrow() throws Exception {
        CheckoutRecord record = new CheckoutRecord(7L, "cart-null", null, null,
                new BigDecimal("10.00"), "COMPLETED");
        when(kafka.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatCode(() -> publisher.publishOrder(record)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publishPayment(record)).doesNotThrowAnyException();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(anyString(), captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);

        JsonNode order = objectMapper.readTree(captor.getAllValues().get(0));
        JsonNode payment = objectMapper.readTree(captor.getAllValues().get(1));
        assertThat(order.get("customerId").asText()).isEmpty();
        assertThat(payment.get("paymentMethod").asText()).isEmpty();
    }

    @Test
    void serializationFailureIsSwallowed() {
        CheckoutRecord record = new CheckoutRecord(1L, "cart-1", "cust-1", "card",
                new BigDecimal("5.00"), "COMPLETED");
        when(kafka.send(anyString(), anyString()))
                .thenThrow(new RuntimeException("broker unreachable"));

        assertThatCode(() -> publisher.publishOrder(record)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publishPayment(record)).doesNotThrowAnyException();
    }
}