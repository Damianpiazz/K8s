package com.ecommerce.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end REST tests for the payment flow (in-memory store).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void validPaymentIsApproved() {
        ResponseEntity<Map> response = rest.postForEntity("/api/payments",
                Map.of("orderId", 1, "amount", 149.48, "method", "card", "cardLast4", "4242"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(response.getBody().get("id")).isNotNull();
        assertThat(response.getBody().get("orderId")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroAmountIsDeclined() {
        ResponseEntity<Map> response = rest.postForEntity("/api/payments",
                Map.of("orderId", 2, "amount", 0, "method", "card"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("DECLINED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void placeholderCard0000IsDeclined() {
        ResponseEntity<Map> response = rest.postForEntity("/api/payments",
                Map.of("orderId", 3, "amount", 50.00, "method", "card", "cardLast4", "0000"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("DECLINED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentsAreQueryableByOrder() {
        rest.postForEntity("/api/payments",
                Map.of("orderId", 77, "amount", 10.00, "method", "paypal"), Map.class);
        rest.postForEntity("/api/payments",
                Map.of("orderId", 77, "amount", 20.00, "method", "paypal"), Map.class);

        ResponseEntity<List> response = rest.getForEntity("/api/payments/order/77", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void unknownPaymentReturns404() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/payments/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}