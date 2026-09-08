package com.ecommerce.search.controller;

import com.ecommerce.search.model.ProductSearchHit;
import com.ecommerce.search.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Search REST API. Mounted under /api/search so the api-gateway forwards
 * /api/search/** untouched.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Search the catalog. Example:
     * {@code GET /api/search?q=keyboard&category=Accessories}.
     *
     * @param q        query term (blank → empty list)
     * @param category optional category filter (case-insensitive)
     * @return matching products with relevance score, best first
     */
    @GetMapping
    public List<ProductSearchHit> search(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String category) {
        return searchService.search(q, category);
    }

    /** Most popular search terms (ranked, capped by {@code search.hot-limit}). */
    @GetMapping("/hot")
    public List<String> hot() {
        return searchService.hotSearches();
    }
}