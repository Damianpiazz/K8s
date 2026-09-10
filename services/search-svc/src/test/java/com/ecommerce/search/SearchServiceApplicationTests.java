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
        // Per the documented rule (docs/casos-de-uso/sistema-ecommerce.md)
        // weights are additive per matching field: name = 3, description = 1,
        // tag = 1. The K87 matches name ("keyboard") AND tag ("keyboard"), so
        // the top hit legitimately scores 4.0 — not 3.0.
        assertThat(first.get("name").toString()).containsIgnoringCase("keyboard");
        assertThat(((Number) first.get("score")).doubleValue()).isEqualTo(4.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void categoryFilterRestrictsResults() {
        ResponseEntity<List> response = rest.getForEntity(
                "/api/search?q=noise&category=Audio", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // "noise" only matches the headset in Audio (description "active noise
        // cancelling" + tag "noise-cancelling"), so the filter must return
        // exactly that hit and nothing from other categories. (The previous
        // query "hd" matched nothing in Audio — the headset has no "hd"
        // substring — so the result was legitimately empty.)
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