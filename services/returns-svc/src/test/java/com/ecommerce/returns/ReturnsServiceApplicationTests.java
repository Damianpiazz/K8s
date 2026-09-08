package com.ecommerce.returns;

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
 * End-to-end REST tests for the returns flow
 * (auto-approval rule, operator approve/reject, order listing).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReturnsServiceApplicationTests {

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
    void allowListedReasonIsAutoApproved() {
        ResponseEntity<Map> created = rest.postForEntity("/api/returns",
                jsonBody(Map.of("orderId", "o-1", "productId", 3L,
                        "reason", "defective", "quantity", 1)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("APPROVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownReasonGoesToPendingReview() {
        // "changed-mind" is NOT on the allow-list → PENDING_REVIEW.
        ResponseEntity<Map> created = rest.postForEntity("/api/returns",
                jsonBody(Map.of("orderId", "o-2", "productId", 3L,
                        "reason", "changed-mind", "quantity", 1)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("PENDING_REVIEW");

        Number id = (Number) created.getBody().get("id");

        // Operator can still approve or reject it.
        ResponseEntity<Map> rejected = rest.exchange("/api/returns/" + id + "/reject",
                HttpMethod.POST, null, Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("status")).isEqualTo("REJECTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void quantityAboveCapGoesToPendingReview() {
        // Quantity 50 > max-quantity-auto-approve (10), even with a good reason.
        ResponseEntity<Map> created = rest.postForEntity("/api/returns",
                jsonBody(Map.of("orderId", "o-3", "productId", 3L,
                        "reason", "defective", "quantity", 50)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsAreQueryableByOrder() {
        rest.postForEntity("/api/returns",
                jsonBody(Map.of("orderId", "o-4", "productId", 1L,
                        "reason", "wrong-item", "quantity", 1)), Map.class);

        ResponseEntity<List> response = rest.getForEntity("/api/returns/order/o-4", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvedReturnCannotBeRejected() {
        ResponseEntity<Map> created = rest.postForEntity("/api/returns",
                jsonBody(Map.of("orderId", "o-5", "productId", 2L,
                        "reason", "not-as-described", "quantity", 1)), Map.class);
        assertThat(created.getBody().get("status")).isEqualTo("APPROVED");
        Number id = (Number) created.getBody().get("id");

        ResponseEntity<Map> rejected = rest.exchange("/api/returns/" + id + "/reject",
                HttpMethod.POST, null, Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidQuantityIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/returns",
                jsonBody(Map.of("orderId", "o-6", "productId", 2L,
                        "reason", "defective", "quantity", 0)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownReturnReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/api/returns/99999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}