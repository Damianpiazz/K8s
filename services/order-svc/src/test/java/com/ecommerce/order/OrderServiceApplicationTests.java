package com.ecommerce.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end REST tests for the order flow (H2, seeded data).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    void seededOrdersAreListed() {
        ResponseEntity<List> response = rest.getForEntity("/api/orders", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2); // DataSeeder
    }

    @Test
    @SuppressWarnings("unchecked")
    void createThenGetOrderRoundTrips() {
        Map<String, Object> newOrder = Map.of(
                "customerId", "cust-99",
                "items", List.of(
                        Map.of("productId", 1, "quantity", 2, "price", 29.99)),
                "total", 59.98);

        ResponseEntity<Map> created = rest.postForEntity(
                "/api/orders", newOrder, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("CREATED");

        Number id = (Number) created.getBody().get("id");
        ResponseEntity<Map> fetched = rest.getForEntity(
                "/api/orders/" + id.longValue(), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("customerId")).isEqualTo("cust-99");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordersAreQueryableByCustomer() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/orders/customer/cust-42", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1); // DataSeeder
    }

    @Test
    void emptyItemsAreRejected() {
        Map<String, Object> badOrder = Map.of(
                "customerId", "cust-1",
                "items", List.of(),
                "total", 0);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/orders", badOrder, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tooManyItemsAreRejected() {
        // 51 items > order.max-items-per-order (50).
        List<Map<String, Object>> items = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> Map.<String, Object>of(
                        "productId", i, "quantity", 1, "price", 1.00))
                .toList();
        Map<String, Object> badOrder = Map.of(
                "customerId", "cust-1",
                "items", items,
                "total", 51);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/orders", badOrder, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownOrderReturns404() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/orders/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}