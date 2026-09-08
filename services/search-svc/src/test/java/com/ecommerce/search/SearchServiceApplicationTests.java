package com.ecommerce.search;

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
 * End-to-end REST tests for the search flow (scoring, filtering, hot list).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchServiceApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    @SuppressWarnings("unchecked")
    void nameMatchScoresHighest() {
        ResponseEntity<List> response = rest.getForEntity("/api/search?q=keyboard", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();

        Map<String, Object> first = (Map<String, Object>) response.getBody().get(0);
        // Name match = 3 → the K87 outranks any description/tag-only hit.
        assertThat(first.get("name").toString()).containsIgnoringCase("keyboard");
        assertThat(((Number) first.get("score")).doubleValue()).isEqualTo(3.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void categoryFilterRestrictsResults() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/search?q=hd&category=Audio", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // "hd" only matches a description/tag of the headset in Audio.
        assertThat(response.getBody()).isNotEmpty();
        for (Object hit : response.getBody()) {
            Map<String, Object> item = (Map<String, Object>) hit;
            assertThat(item.get("category")).isEqualTo("Audio");
        }
    }

    @Test
    void blankQueryReturnsEmptyList() {
        ResponseEntity<List> response = rest.getForEntity("/api/search?q=", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void hotSearchesReflectQueryVolume() {
        rest.getForEntity("/api/search?q=stand", List.class);
        rest.getForEntity("/api/search?q=stand", List.class);
        rest.getForEntity("/api/search?q=monitor", List.class);

        ResponseEntity<List> response = rest.getForEntity("/api/search/hot", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(0)).isEqualTo("stand"); // most frequent
    }
}