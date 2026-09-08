package com.ecommerce.analytics.service;

import com.ecommerce.analytics.model.AnalyticsEvent;
import com.ecommerce.analytics.model.EventType;
import com.ecommerce.analytics.model.TopProduct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory event log + aggregated counters.
 *
 * <p>Write path: events append to a bounded {@link ConcurrentLinkedQueue} and
 * update two counter maps (by event type, by product id) in O(1). Read path:
 * reports derive from the counters (summary, top-products) or scan the recent
 * buffer (event list). The buffer is capped at {@code analytics.max-events-buffer}
 * so memory stays bounded.
 *
 * <p>Production design: events stream to Azure Event Hubs (Kafka-compatible)
 * and an aggregator (Azure Functions / Stream Analytics) writes per-minute
 * rollups; the report endpoints then read pre-aggregated tables instead of a
 * per-pod buffer. See README.
 */
@Service
public class AnalyticsService {

    private final ConcurrentLinkedQueue<AnalyticsEvent> events = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<EventType, AtomicLong> typeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> productCounts = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private final int maxEventsBuffer;

    public AnalyticsService(@Value("${analytics.max-events-buffer:10000}") int maxEventsBuffer) {
        this.maxEventsBuffer = maxEventsBuffer;
    }

    /** Records an event and updates the aggregated counters. */
    public AnalyticsEvent record(EventType type, Long productId, String customerId, Double value) {
        AnalyticsEvent event = new AnalyticsEvent(ids.getAndIncrement(), type,
                productId, customerId, value, Instant.now());
        events.add(event);
        typeCounts.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        if (productId != null) {
            productCounts.computeIfAbsent(productId, k -> new AtomicLong()).incrementAndGet();
        }
        // Bounded buffer: drop the oldest event when over the cap.
        while (events.size() > maxEventsBuffer) {
            events.poll();
        }
        return event;
    }

    /** Events in the buffer, optionally filtered by type (newest first). */
    public List<AnalyticsEvent> list(EventType type) {
        return events.stream()
                .filter(e -> type == null || e.type() == type)
                .sorted(Comparator.comparingLong(AnalyticsEvent::id).reversed())
                .toList();
    }

    /** Most viewed products by event count, capped at the limit. */
    public List<TopProduct> topProducts(int limit) {
        return productCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, AtomicLong>>comparingByValue(
                                Comparator.comparingLong(AtomicLong::get))
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(e -> new TopProduct(e.getKey(), e.getValue().get()))
                .toList();
    }

    /** Totals per event type. */
    public Map<EventType, Long> summary() {
        return typeCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}