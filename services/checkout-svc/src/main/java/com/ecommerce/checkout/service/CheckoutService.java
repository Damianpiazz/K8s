package com.ecommerce.checkout.service;

import com.ecommerce.checkout.model.CheckoutRecord;
import com.ecommerce.checkout.model.CheckoutRequest;
import com.ecommerce.checkout.model.CheckoutResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checkout flow orchestrator.
 *
 * <p>Steps: (1) load the cart from cart-svc, (2) compute the total,
 * (3) record the checkout. The order is created <em>in-memory</em> here to
 * keep the service standalone — in production this step is a POST to
 * order-svc {@code /api/orders} (persisted in Postgres), followed by a
 * payment-svc call, and the result emitted on the {@code order-events} topic.
 *
 * <p>Resilience demo: if cart-svc is unreachable the checkout falls back to a
 * mock (empty) cart instead of failing — the store must never lose a sale
 * because a sibling service is down.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** Cart payload as returned by cart-svc GET /api/cart/{cartId}. */
    private record CartResponse(String id, List<CartItemResponse> items, BigDecimal total) {
    }

    private record CartItemResponse(long productId, int quantity, BigDecimal unitPrice) {
    }

    private final ConcurrentHashMap<Long, CheckoutRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private final RestClient cartClient;

    public CheckoutService(
            @Value("${checkout.cart.base-url}") String cartBaseUrl,
            @Value("${checkout.cart.timeout-ms}") long cartTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cartTimeoutMs);
        factory.setReadTimeout((int) cartTimeoutMs);
        this.cartClient = RestClient.builder()
                .baseUrl(cartBaseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Runs the checkout flow and stores the record. Never throws on cart failure. */
    public CheckoutResponse checkout(CheckoutRequest request) {
        if (request.cartId() == null || request.cartId().isBlank()) {
            throw new IllegalArgumentException("cartId is required");
        }

        BigDecimal total = fetchCartTotal(request.cartId());
        long orderId = ids.getAndIncrement();
        String status = "COMPLETED";

        CheckoutRecord record = new CheckoutRecord(orderId, request.cartId(),
                request.customerId(), request.paymentMethod(), total, status);
        records.put(orderId, record);
        log.info("Checkout {} for customer {} completed — total {}",
                orderId, request.customerId(), total);

        return new CheckoutResponse(orderId, total, status);
    }

    public Optional<CheckoutRecord> findById(long id) {
        return Optional.ofNullable(records.get(id));
    }

    public List<CheckoutRecord> findAll() {
        return List.copyOf(records.values());
    }

    /**
     * Reads the cart total from cart-svc; falls back to a mock (empty) cart on
     * any failure so the service keeps working standalone.
     */
    private BigDecimal fetchCartTotal(String cartId) {
        try {
            CartResponse cart = cartClient.get()
                    .uri("/api/cart/{cartId}", cartId)
                    .retrieve()
                    .body(CartResponse.class);
            log.info("Cart {} fetched from cart-svc — total {}", cartId,
                    cart != null ? cart.total() : null);
            return cart != null && cart.total() != null
                    ? cart.total()
                    : BigDecimal.ZERO;
        } catch (RuntimeException e) {
            // cart-svc down or malformed payload → mock cart (standalone demo).
            log.warn("cart-svc unavailable for cart {} ({}): using mock empty cart",
                    cartId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}