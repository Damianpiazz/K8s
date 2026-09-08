package com.ecommerce.catalog;

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
 * End-to-end REST test against the real context (H2, seeded data).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    void seededProductsAreListed() {
        ResponseEntity<List> response =
                rest.getForEntity("/api/catalog/products", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(4); // DataSeeder
    }

    @Test
    @SuppressWarnings("unchecked")
    void createThenGetProductRoundTrips() {
        Map<String, Object> newProduct = Map.of(
                "name", "Webcam HD-720",
                "description", "1080p Webcam with microphone",
                "price", 59.99,
                "stock", 25,
                "category", "Accessories");

        ResponseEntity<Map> created = rest.postForEntity(
                "/api/catalog/products", newProduct, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Number id = (Number) created.getBody().get("id");
        ResponseEntity<Map> fetched = rest.getForEntity(
                "/api/catalog/products/" + id.longValue(), Map.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("name")).isEqualTo("Webcam HD-720");
        assertThat(new BigDecimal(fetched.getBody().get("price").toString()))
                .isEqualByComparingTo(new BigDecimal("59.99"));
    }

    @Test
    void unknownProductReturns404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/catalog/products/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}