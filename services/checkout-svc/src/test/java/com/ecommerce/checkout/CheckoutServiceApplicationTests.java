package com.ecommerce.checkout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end REST tests for the checkout flow. No cart-svc is running in
 * tests, so every checkout exercises the mock-cart fallback (standalone demo).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkoutCompletesWithMockCartFallback() {
        Map<String, Object> request = Map.of(
                "cartId", "session-1",
                "customerId", "cust-42",
                "paymentMethod", "card");

        ResponseEntity<Map> response = rest.postForEntity(
                "/api/checkout", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("orderId")).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("COMPLETED");
        // cart-svc is absent → mock empty cart → zero total.
        assertThat(new BigDecimal(response.getBody().get("total").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedCheckoutIsFetchableById() {
        Map<String, Object> request = Map.of(
                "cartId", "session-2",
                "customerId", "cust-7",
                "paymentMethod", "paypal");

        ResponseEntity<Map> created = rest.postForEntity(
                "/api/checkout", request, Map.class);
        Number id = (Number) created.getBody().get("orderId");

        ResponseEntity<Map> fetched = rest.getForEntity(
                "/api/checkout/" + id.longValue(), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("cartId")).isEqualTo("session-2");
        assertThat(fetched.getBody().get("customerId")).isEqualTo("cust-7");
    }

    @Test
    void missingCartIdIsRejected() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/checkout", Map.of("customerId", "cust-1"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownCheckoutReturns404() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/checkout/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}