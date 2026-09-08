package com.ecommerce.search.model;

/**
 * One search result: the matched product plus its relevance score.
 *
 * @param id          product id (mirrors the demo catalog ids)
 * @param name        product name
 * @param description short description
 * @param category    product category
 * @param score       relevance: name match 3, description match 1, tag match 1
 */
public record ProductSearchHit(long id, String name, String description,
                               String category, double score) {
}