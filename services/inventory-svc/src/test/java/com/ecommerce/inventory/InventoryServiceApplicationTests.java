package com.ecommerce.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end REST tests for the inventory flow
 * (list → reserve → release/confirm, oversell rejected).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void seededSkusAreListed() {
        ResponseEntity<List> response = rest.getForEntity("/api/inventory", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(8); // DataSeeder
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveThenReleaseRoundTrips() {
        // Reserve 3 of product 1 (120 on hand).
        ResponseEntity<Map> reserved = rest.postForEntity("/api/inventory/reserve",
                jsonBody(Map.of("productId", 1L, "quantity", 3)), Map.class);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserved.getBody().get("status")).isEqualTo("RESERVED");
        Number reservationId = (Number) reserved.getBody().get("id");

        // Reserved count is now visible on the item.
        ResponseEntity<Map> item = rest.getForEntity("/api/inventory/1", Map.class);
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item.getBody().get("reserved")).isEqualTo(3);

        // Release it back.
        ResponseEntity<Map> released = rest.postForEntity("/api/inventory/release",
                jsonBody(Map.of("reservationId", reservationId.longValue())), Map.class);
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody().get("status")).isEqualTo("RELEASED");

        ResponseEntity<Map> after = rest.getForEntity("/api/inventory/1", Map.class);
        assertThat(after.getBody().get("reserved")).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmCommitsReservation() {
        ResponseEntity<Map> reserved = rest.postForEntity("/api/inventory/reserve",
                jsonBody(Map.of("productId", 3L, "quantity", 2)), Map.class);
        Number reservationId = (Number) reserved.getBody().get("id");

        ResponseEntity<Map> confirmed = rest.exchange("/api/inventory/confirm/" + reservationId,
                HttpMethod.POST, null, Map.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("status")).isEqualTo("CONFIRMED");

        // Confirming an already-released/confirmed reservation → 409.
        ResponseEntity<Map> again = rest.exchange("/api/inventory/confirm/" + reservationId,
                HttpMethod.POST, null, Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void oversellIsRejectedWith409() {
        // Product 3 has 18 on hand; try to reserve 100.
        ResponseEntity<Map> response = rest.postForEntity("/api/inventory/reserve",
                jsonBody(Map.of("productId", 3L, "quantity", 100)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownProductReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/api/inventory/999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateStockReplacesLevel() {
        rest.put("/api/inventory/5", jsonBody(Map.of("quantity", 12)));
        ResponseEntity<Map> item = rest.getForEntity("/api/inventory/5", Map.class);
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item.getBody().get("stockLevel")).isEqualTo(12);
    }
}