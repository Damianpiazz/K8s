package com.ecommerce.recommendation;

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
 * End-to-end REST tests for the recommendation flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecommendationServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerGetsRecommendations() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/recommendations?customerId=cust-1", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        Map<String, Object> first = (Map<String, Object>) response.getBody().get(0);
        assertThat(first).containsKey("productId");
        assertThat(first).containsKey("reason");
    }

    @Test
    void unknownCustomerFallsBackToPopular() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/recommendations?customerId=nobody", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void similarProductsAreReturned() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/recommendations/cust-1/similar?productId=1", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        // Recommendations never include the seed product itself.
        for (Object rec : response.getBody()) {
            Map<String, Object> item = (Map<String, Object>) rec;
            assertThat(((Number) item.get("productId")).longValue()).isNotEqualTo(1L);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitIsRespectedAndCapped() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/recommendations?customerId=cust-1&limit=100", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // max-limit is 10.
        assertThat(response.getBody().size()).isLessThanOrEqualTo(10);
    }
}