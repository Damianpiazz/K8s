package com.ecommerce.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end REST tests for the analytics flow
 * (ingest → filter → reports).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsServiceApplicationTests {

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
    void recordEventThenQueryByType() {
        ResponseEntity<Map> created = rest.postForEntity("/api/analytics/events",
                jsonBody(Map.of("type", "VIEW", "productId", 3L, "customerId", "cust-1")), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("type")).isEqualTo("VIEW");
        assertThat(created.getBody()).containsKey("id");
        assertThat(created.getBody()).containsKey("timestamp");

        ResponseEntity<List> filtered = rest.getForEntity(
                "/api/analytics/events?type=VIEW", List.class);
        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filtered.getBody()).isNotEmpty();
        for (Object event : filtered.getBody()) {
            assertThat(((Map<String, Object>) event).get("type")).isEqualTo("VIEW");
        }
    }

    @Test
    void unknownTypeIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/analytics/events",
                jsonBody(Map.of("type", "HOVER")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryReflectsCounts() {
        rest.postForEntity("/api/analytics/events",
                jsonBody(Map.of("type", "VIEW", "productId", 1L)), Map.class);
        rest.postForEntity("/api/analytics/events",
                jsonBody(Map.of("type", "VIEW", "productId", 2L)), Map.class);
        rest.postForEntity("/api/analytics/events",
                jsonBody(Map.of("type", "PURCHASE", "productId", 1L, "value", 29.99)), Map.class);

        ResponseEntity<Map> summary = rest.getForEntity("/api/analytics/reports/summary", Map.class);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Self-contained: these counts come only from THIS test's posts above.
        assertThat(summary.getBody().get("VIEW").toString()).isEqualTo("2");
        assertThat(summary.getBody().get("PURCHASE").toString()).isEqualTo("1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void topProductsOrdersByCount() {
        // product 5 gets 3 events, product 7 gets 1.
        for (int i = 0; i < 3; i++) {
            rest.postForEntity("/api/analytics/events",
                    jsonBody(Map.of("type", "CLICK", "productId", 5L)), Map.class);
        }
        rest.postForEntity("/api/analytics/events",
                jsonBody(Map.of("type", "CLICK", "productId", 7L)), Map.class);

        ResponseEntity<List> top = rest.getForEntity(
                "/api/analytics/reports/top-products?limit=3", List.class);
        assertThat(top.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(top.getBody()).isNotEmpty();
        Map<String, Object> first = (Map<String, Object>) top.getBody().get(0);
        assertThat(((Number) first.get("productId")).longValue()).isEqualTo(5L);
        assertThat(((Number) first.get("count")).longValue()).isEqualTo(3);
    }
}