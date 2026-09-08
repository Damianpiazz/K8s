package com.ecommerce.analytics.controller;

import com.ecommerce.analytics.model.AnalyticsEvent;
import com.ecommerce.analytics.model.EventType;
import com.ecommerce.analytics.model.TopProduct;
import com.ecommerce.analytics.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Analytics REST API. Mounted under /api/analytics so the api-gateway forwards
 * /api/analytics/** untouched.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final int defaultTopLimit;

    public AnalyticsController(AnalyticsService analytics,
                               @Value("${analytics.default-top-limit:5}") int defaultTopLimit) {
        this.analytics = analytics;
        this.defaultTopLimit = defaultTopLimit;
    }

    /** Request body: {"type": "VIEW", "productId": 3, "customerId": "cust-1", "value": 29.99}. */
    public record EventRequest(String type, Long productId, String customerId, Double value) {
    }

    /** Accepts an event (write path) and returns it with id + timestamp. */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public AnalyticsEvent record(@RequestBody EventRequest request) {
        EventType type;
        try {
            type = EventType.valueOf(request.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown event type: " + request.type());
        }
        return analytics.record(type, request.productId(), request.customerId(), request.value());
    }

    /** Recent events, optionally filtered by type (newest first). */
    @GetMapping("/events")
    public List<AnalyticsEvent> events(
            @RequestParam(required = false) String type) {
        EventType filter = null;
        if (type != null && !type.isBlank()) {
            try {
                filter = EventType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown event type: " + type);
            }
        }
        return analytics.list(filter);
    }

    /** Most popular products by event count. {@code limit} defaults to 5. */
    @GetMapping("/reports/top-products")
    public List<TopProduct> topProducts(
            @RequestParam(required = false) Integer limit) {
        int n = limit != null ? Math.max(limit, 1) : defaultTopLimit;
        return analytics.topProducts(n);
    }

    /** Totals per event type. */
    @GetMapping("/reports/summary")
    public Map<EventType, Long> summary() {
        return analytics.summary();
    }
}