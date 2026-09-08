package com.ecommerce.notification;

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
 * End-to-end REST tests for the notification flow. The Kafka consumer is
 * disabled by default (notifications.kafka.enabled=false), so these tests run
 * with zero broker infrastructure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void createThenGetNotificationRoundTrips() {
        Map<String, Object> request = Map.of(
                "type", "ORDER_CONFIRMED",
                "recipient", "customer@example.com",
                "subject", "Your order is confirmed",
                "body", "Order #42 has been received.");

        ResponseEntity<Map> created = rest.postForEntity(
                "/api/notifications", request, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("type")).isEqualTo("ORDER_CONFIRMED");
        assertThat(created.getBody().get("recipient")).isEqualTo("customer@example.com");

        Number id = (Number) created.getBody().get("id");
        ResponseEntity<Map> fetched = rest.getForEntity(
                "/api/notifications/" + id.longValue(), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("subject")).isEqualTo("Your order is confirmed");
    }

    @Test
    void unknownTypeIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/notifications",
                Map.of("type", "NOT_A_REAL_TYPE", "recipient", "a@b.c",
                        "subject", "x", "body", "y"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notificationsAreListed() {
        rest.postForEntity("/api/notifications",
                Map.of("type", "SYSTEM", "recipient", "ops@example.com",
                        "subject", "Maintenance", "body", "tonight 02:00 UTC"),
                Map.class);
        rest.postForEntity("/api/notifications",
                Map.of("type", "PAYMENT_RECEIVED", "recipient", "ops@example.com",
                        "subject", "Payment", "body", "ok"),
                Map.class);

        ResponseEntity<List> response = rest.getForEntity("/api/notifications", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void unknownNotificationReturns404() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/notifications/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}