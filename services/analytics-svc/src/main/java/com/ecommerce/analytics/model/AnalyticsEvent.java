package com.ecommerce.analytics.model;

import java.time.Instant;

/**
 * One ingested analytics event.
 *
 * @param id         sequential event id
 * @param type       event type (see {@link EventType})
 * @param productId  product the event refers to (nullable)
 * @param customerId customer the event refers to (nullable)
 * @param value      optional numeric payload, e.g. price or quantity (nullable)
 * @param timestamp  ingestion time
 */
public record AnalyticsEvent(long id, EventType type, Long productId,
                             String customerId, Double value, Instant timestamp) {
}