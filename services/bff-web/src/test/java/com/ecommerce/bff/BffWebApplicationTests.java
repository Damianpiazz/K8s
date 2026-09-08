package com.ecommerce.bff;

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
 * End-to-end tests for the BFF degraded path. No catalog-svc / cart-svc run in
 * tests, so the WebClient calls fail fast (connection refused) and every
 * response must be a 200 with a {@code degraded: true} partial payload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BffWebApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void homeDegradesGracefullyWhenCatalogIsDown() {
        ResponseEntity<Map> response = rest.getForEntity("/api/bff/home", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("degraded")).isEqualTo(true);
        assertThat((List<?>) response.getBody().get("products")).isEmpty();
        assertThat(response.getBody().get("hero")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cartDegradesGracefullyWhenCartIsDown() {
        ResponseEntity<Map> response = rest.getForEntity("/api/bff/cart/session-1", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("degraded")).isEqualTo(true);
        assertThat((List<?>) response.getBody().get("items")).isEmpty();
    }
}