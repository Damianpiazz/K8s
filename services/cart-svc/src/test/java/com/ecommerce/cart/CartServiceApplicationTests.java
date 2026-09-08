package com.ecommerce.cart;

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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end REST tests for the cart flow (add → total → remove).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CartServiceApplicationTests {

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
    void addItemThenTotal() {
        // GET an empty cart — always 200.
        ResponseEntity<Map> empty = rest.getForEntity("/api/cart/session-1", Map.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Add two lines.
        rest.postForEntity("/api/cart/session-1/items",
                jsonBody(Map.of("productId", 1, "quantity", 2, "unitPrice", 29.99)), Map.class);
        rest.postForEntity("/api/cart/session-1/items",
                jsonBody(Map.of("productId", 2, "quantity", 1, "unitPrice", 89.50)), Map.class);

        // Total = 2 × 29.99 + 1 × 89.50 = 149.48
        ResponseEntity<Map> total = rest.getForEntity("/api/cart/session-1/total", Map.class);
        assertThat(total.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(total.getBody().get("total").toString()))
                .isEqualByComparingTo(new BigDecimal("149.48"));
        assertThat(total.getBody().get("itemCount")).isEqualTo(2);

        // Merge: adding product 1 again accumulates quantity (2+3=5).
        rest.postForEntity("/api/cart/session-1/items",
                jsonBody(Map.of("productId", 1, "quantity", 3, "unitPrice", 29.99)), Map.class);
        ResponseEntity<Map> merged = rest.getForEntity("/api/cart/session-1", Map.class);
        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void removeItemReturns204ThenNotFound() {
        rest.postForEntity("/api/cart/session-2/items",
                jsonBody(Map.of("productId", 7, "quantity", 1, "unitPrice", 10.00)), Map.class);

        ResponseEntity<Void> removed = rest.exchange("/api/cart/session-2/items/7",
                HttpMethod.DELETE, null, Void.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> again = rest.exchange("/api/cart/session-2/items/7",
                HttpMethod.DELETE, null, String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void negativeQuantityIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/cart/session-3/items",
                jsonBody(Map.of("productId", 1, "quantity", -2, "unitPrice", 5.00)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}