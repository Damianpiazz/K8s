package com.ecommerce.shipping;

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
 * End-to-end REST tests for the shipping flow
 * (create → PENDING → SHIPPED (tracking) → DELIVERED).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShippingServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> shippingBody(String orderId) {
        return Map.of(
                "orderId", orderId,
                "address", Map.of("line1", "Main St 1", "city", "Madrid",
                        "postalCode", "28001", "country", "ES"));
    }

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void createThenGetRoundTrips() {
        ResponseEntity<Map> created = rest.postForEntity("/api/shipping",
                jsonBody(shippingBody("order-1")), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("PENDING");
        assertThat(created.getBody().get("carrier")).isEqualTo("Ecommerce Express");

        Number id = (Number) created.getBody().get("id");
        ResponseEntity<Map> fetched = rest.getForEntity("/api/shipping/" + id, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("orderId")).isEqualTo("order-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipmentsAreQueryableByOrder() {
        rest.postForEntity("/api/shipping", jsonBody(shippingBody("order-2")), Map.class);
        ResponseEntity<List> response = rest.getForEntity("/api/shipping/order/order-2", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipThenDeliverAssignsTrackingNumber() {
        ResponseEntity<Map> created = rest.postForEntity("/api/shipping",
                jsonBody(shippingBody("order-3")), Map.class);
        Number id = (Number) created.getBody().get("id");

        // PENDING → SHIPPED generates a tracking number.
        ResponseEntity<Map> shipped = rest.exchange("/api/shipping/" + id + "/status",
                HttpMethod.PATCH, jsonBody(Map.of("status", "SHIPPED")), Map.class);
        assertThat(shipped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shipped.getBody().get("status")).isEqualTo("SHIPPED");
        assertThat(shipped.getBody().get("trackingNumber").toString()).startsWith("EC");

        // SHIPPED → DELIVERED.
        ResponseEntity<Map> delivered = rest.exchange("/api/shipping/" + id + "/status",
                HttpMethod.PATCH, jsonBody(Map.of("status", "DELIVERED")), Map.class);
        assertThat(delivered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delivered.getBody().get("status")).isEqualTo("DELIVERED");
    }

    @Test
    void terminalShipmentRefusesFurtherTransitions() {
        ResponseEntity<Map> created = rest.postForEntity("/api/shipping",
                jsonBody(shippingBody("order-4")), Map.class);
        Number id = (Number) created.getBody().get("id");

        rest.exchange("/api/shipping/" + id + "/status",
                HttpMethod.PATCH, jsonBody(Map.of("status", "DELIVERED")), Map.class);
        ResponseEntity<Map> again = rest.exchange("/api/shipping/" + id + "/status",
                HttpMethod.PATCH, jsonBody(Map.of("status", "SHIPPED")), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownStatusValueIsRejected() {
        ResponseEntity<Map> created = rest.postForEntity("/api/shipping",
                jsonBody(shippingBody("order-5")), Map.class);
        Number id = (Number) created.getBody().get("id");

        ResponseEntity<Map> bad = rest.exchange("/api/shipping/" + id + "/status",
                HttpMethod.PATCH, jsonBody(Map.of("status", "TELEPORTED")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownShipmentReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/api/shipping/99999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}