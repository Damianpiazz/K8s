package com.ecommerce.recommendation.model;

/**
 * One recommended product with an explainable reason.
 *
 * @param productId recommended product id
 * @param name      product name
 * @param category  product category
 * @param score     relevance score (higher = more relevant)
 * @param reason    human-readable explanation (also-bought / same-category)
 */
public record Recommendation(long productId, String name, String category,
                             double score, String reason) {
}