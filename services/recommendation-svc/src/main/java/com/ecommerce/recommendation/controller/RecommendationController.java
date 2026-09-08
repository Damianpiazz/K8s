package com.ecommerce.recommendation.controller;

import com.ecommerce.recommendation.model.Recommendation;
import com.ecommerce.recommendation.service.RecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recommendation REST API. Mounted under /api/recommendations so the api-gateway
 * forwards /api/recommendations/** untouched.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    /**
     * Recommendations for a customer. Example:
     * {@code GET /api/recommendations?customerId=cust-3&limit=3}.
     */
    @GetMapping
    public List<Recommendation> forCustomer(
            @RequestParam String customerId,
            @RequestParam(required = false) Integer limit) {
        return recommendations.forCustomer(customerId, limit);
    }

    /**
     * Products similar to a product, personalized for a customer. Example:
     * {@code GET /api/recommendations/cust-3/similar?productId=1}.
     */
    @GetMapping("/{customerId}/similar")
    public List<Recommendation> similar(@PathVariable String customerId,
                                        @RequestParam long productId,
                                        @RequestParam(required = false) Integer limit) {
        return recommendations.similar(customerId, productId, limit);
    }
}