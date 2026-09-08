package com.ecommerce.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Product search service.
 *
 * <p>Read-only in-memory index seeded from a small static catalog — the same
 * demo style as catalog-svc's seeder, but without a database. Production would
 * back this with a real search index (Azure AI Search / Elasticsearch); see
 * SearchService and the README.
 */
@SpringBootApplication
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}