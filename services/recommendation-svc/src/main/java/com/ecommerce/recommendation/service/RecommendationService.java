package com.ecommerce.recommendation.service;

import com.ecommerce.recommendation.model.Recommendation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory collaborative-ish recommendations.
 *
 * <p>A seed "purchases" map (customer → product ids they bought) plus a small
 * product catalog let us recommend:
 * <ul>
 *   <li><b>by customer</b>: products bought by "similar" customers (shared
 *       purchased items), and products in categories the customer already buys.</li>
 *   <li><b>similar</b>: items bought together with the given product
 *       (also-bought), falling back to same-category.</li>
 * </ul>
 * Scoring is deliberately simple (count of shared purchases + category boost)
 * so the demo is deterministic and demonstrable.
 *
 * <p>Production design: offline ML ranks candidates daily and caches the top-N
 * per customer in Redis (Azure Cache for Redis); reads are O(N) cache lookups
 * instead of recomputing scores at request time.
 */
@Service
public class RecommendationService {

    /** Seed product catalog (id → name/category) — mirrors search/inventory ids. */
    record Product(long id, String name, String category) {
    }

    private static final Map<Long, Product> PRODUCTS = Map.of(
            1L, new Product(1L, "Wireless Mouse MX-10", "Accessories"),
            2L, new Product(2L, "Mechanical Keyboard K87", "Accessories"),
            3L, new Product(3L, "27\" IPS Monitor", "Displays"),
            4L, new Product(4L, "USB-C Docking Station", "Accessories"),
            5L, new Product(5L, "Laptop Stand Pro", "Accessories"),
            6L, new Product(6L, "Webcam HD 1080p", "Accessories"),
            7L, new Product(7L, "Bluetooth Headset NC", "Audio"),
            8L, new Product(8L, "Smartwatch S2", "Wearables")
    );

    private static final Map<String, List<Long>> PURCHASES = Map.of(
            "cust-1", List.of(1L, 2L, 4L),
            "cust-2", List.of(2L, 3L, 7L),
            "cust-3", List.of(1L, 4L, 5L),
            "cust-42", List.of(3L, 6L, 8L)
    );

    /** Products bought together (an "also-bought" graph, symmetric-ish). */
    private static final Map<Long, List<Long>> ALSO_BOUGHT = Map.of(
            1L, List.of(2L, 4L, 5L),
            2L, List.of(1L, 4L),
            3L, List.of(4L, 5L),
            4L, List.of(1L, 2L, 5L),
            5L, List.of(2L, 4L),
            6L, List.of(3L, 8L),
            7L, List.of(2L, 3L)
    );

    private final int defaultLimit;
    private final int maxLimit;

    public RecommendationService(@Value("${recommendation.default-limit:5}") int defaultLimit,
                                 @Value("${recommendation.max-limit:10}") int maxLimit) {
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    /**
     * Recommendations for a customer.
     *
     * @param customerId customer id (unknown ids yield the seed catalog's most
     *                   popular items)
     * @param limit      capped at {@code recommendation.max-limit}
     */
    public List<Recommendation> forCustomer(String customerId, Integer limit) {
        int n = clamp(limit);
        Set<Long> bought = new LinkedHashSet<>(PURCHASES.getOrDefault(customerId, List.of()));
        Set<Long> recommended = new LinkedHashSet<>();

        // Similar customers: someone else bought a product I bought too → their
        // other purchases are candidates (also-bought reason).
        for (Map.Entry<String, List<Long>> e : PURCHASES.entrySet()) {
            if (e.getKey().equals(customerId)) {
                continue;
            }
            List<Long> theirs = e.getValue();
            boolean shared = theirs.stream().anyMatch(bought::contains);
            if (shared) {
                for (Long id : theirs) {
                    if (!bought.contains(id)) {
                        recommended.add(id);
                    }
                }
            }
        }
        // Category affinity: products in categories the customer already bought
        // (same-category reason), keeping the set small.
        if (recommended.size() < n) {
            Set<String> ownedCategories = new LinkedHashSet<>();
            for (Long id : bought) {
                Product p = PRODUCTS.get(id);
                if (p != null) {
                    ownedCategories.add(p.category());
                }
            }
            for (Product p : PRODUCTS.values()) {
                if (!bought.contains(p.id()) && ownedCategories.contains(p.category())) {
                    recommended.add(p.id());
                }
            }
        }
        // Fallback: popular items (most also-bought links) for sparse profiles.
        if (recommended.isEmpty()) {
            recommended.add(1L);
            recommended.add(4L);
            recommended.add(2L);
        }
        return recommended.stream()
                .limit(n)
                .map(id -> toRecommendation(id, reason(id, bought)))
                .toList();
    }

    /**
     * Products similar to a given product for a customer.
     *
     * @param customerId customer id (for personalization; unused in the naive
     *                   scoring but kept in the contract)
     * @param productId  the seed product
     * @param limit      capped at {@code recommendation.max-limit}
     */
    public List<Recommendation> similar(String customerId, long productId, Integer limit) {
        int n = clamp(limit);
        Set<Long> out = new LinkedHashSet<>();
        out.addAll(ALSO_BOUGHT.getOrDefault(productId, List.of()));
        // Same-category fallback when also-bought is too thin.
        Product seed = PRODUCTS.get(productId);
        if (seed != null && out.size() < n) {
            for (Product p : PRODUCTS.values()) {
                if (p.id() != productId && p.category().equals(seed.category())) {
                    out.add(p.id());
                }
            }
        }
        return out.stream()
                .filter(id -> id != productId)
                .limit(n)
                .map(id -> toRecommendation(id, ALSO_BOUGHT.getOrDefault(productId, List.of()).contains(id)
                        ? "also-bought" : "same-category"))
                .toList();
    }

    private Recommendation toRecommendation(long id, String reason) {
        Product p = PRODUCTS.get(id);
        if (p == null) {
            return new Recommendation(id, "Product " + id, "Unknown", 0.0, reason);
        }
        double score = reason.equals("also-bought") ? 1.5 : 1.0;
        return new Recommendation(p.id(), p.name(), p.category(), score, reason);
    }

    private String reason(long id, Set<Long> bought) {
        // Prefer an "also-bought" explanation when the neighbour purchase applies.
        for (var entry : ALSO_BOUGHT.entrySet()) {
            if (entry.getValue().contains(id)) {
                return "also-bought";
            }
        }
        return "same-category";
    }

    private int clamp(Integer limit) {
        if (limit == null) {
            return defaultLimit;
        }
        return Math.min(Math.max(limit, 1), maxLimit);
    }
}