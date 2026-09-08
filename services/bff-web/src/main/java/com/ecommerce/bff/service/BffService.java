package com.ecommerce.bff.service;

import com.ecommerce.bff.model.CartItemView;
import com.ecommerce.bff.model.CartView;
import com.ecommerce.bff.model.HomeResponse;
import com.ecommerce.bff.model.ProductSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking aggregation of catalog + cart into storefront views.
 *
 * <p>Every backend call is wrapped so failures (connection refused, 2s
 * timeout, 5xx, bad payload) degrade to a partial response with
 * {@code degraded: true} — the demo shows resilience: the storefront stays up
 * even when a sibling service is down.
 */
@Service
public class BffService {

    private static final Logger log = LoggerFactory.getLogger(BffService.class);

    /** Product payload as returned by catalog-svc GET /api/catalog/products. */
    private record CatalogProduct(long id, String name, String description,
                                  BigDecimal price, String category) {
    }

    /** Cart payload as returned by cart-svc GET /api/cart/{cartId}. */
    private record CartPayload(String id, List<CartItemPayload> items, BigDecimal total) {
    }

    private record CartItemPayload(long productId, int quantity, BigDecimal unitPrice) {
    }

    private final WebClient catalogClient;
    private final WebClient cartClient;

    public BffService(WebClient catalogClient, WebClient cartClient) {
        this.catalogClient = catalogClient;
        this.cartClient = cartClient;
    }

    /** Homepage: product grid + hero. Degrades to empty grid on catalog failure. */
    public Mono<HomeResponse> home() {
        return fetchProducts()
                .map(this::toHomePage)
                .onErrorReturn(HomeResponse.degraded());
    }

    /** Cart joined with product info. Degrades on cart failure; per-item lookups degrade individually. */
    public Mono<CartView> cart(String cartId) {
        return fetchCart(cartId)
                .flatMap(this::joinProducts)
                .onErrorReturn(CartView.degraded(cartId));
    }

    // ── backend calls ──────────────────────────────────────────────────────

    private Mono<List<CatalogProduct>> fetchProducts() {
        return catalogClient.get()
                .uri("/api/catalog/products")
                .retrieve()
                .bodyToFlux(CatalogProduct.class)
                .collectList();
    }

    private Mono<CartPayload> fetchCart(String cartId) {
        return cartClient.get()
                .uri("/api/cart/{cartId}", cartId)
                .retrieve()
                .bodyToMono(CartPayload.class);
    }

    private Mono<CatalogProduct> fetchProduct(long productId) {
        return catalogClient.get()
                .uri("/api/catalog/products/{id}", productId)
                .retrieve()
                .bodyToMono(CatalogProduct.class);
    }

    // ── shaping ────────────────────────────────────────────────────────────

    private HomeResponse toHomePage(List<CatalogProduct> products) {
        List<ProductSummary> grid = products.stream()
                .map(p -> new ProductSummary(p.id(), p.name(), p.description(),
                        p.price(), p.category()))
                .toList();
        // Hero rule: first "Displays" product, else the first product.
        ProductSummary hero = grid.stream()
                .filter(p -> "Displays".equalsIgnoreCase(p.category()))
                .findFirst()
                .orElse(grid.isEmpty() ? null : grid.get(0));
        return new HomeResponse(grid, hero, false);
    }

    private Mono<CartView> joinProducts(CartPayload cart) {
        if (cart.items() == null || cart.items().isEmpty()) {
            return Mono.just(new CartView(cart.id(), List.of(), cart.total(), false));
        }
        // Join product lookups in parallel; a failed lookup degrades to
        // "Product #<id>" (see toItemView).
        List<Mono<CartItemView>> itemMonos = cart.items().stream()
                .map(this::toItemView)
                .toList();
        return Mono.zip(itemMonos, (Object[] results) -> {
            List<CartItemView> views = new ArrayList<>(results.length);
            for (Object result : results) {
                views.add((CartItemView) result);
            }
            return new CartView(cart.id(), views, cart.total(), false);
        });
    }

    private Mono<CartItemView> toItemView(CartItemPayload item) {
        return fetchProduct(item.productId())
                .map(p -> new CartItemView(item.productId(), p.name(),
                        item.quantity(), item.unitPrice(), null))
                .onErrorReturn(new CartItemView(item.productId(),
                        "Product #" + item.productId(), item.quantity(),
                        item.unitPrice(), null));
    }
}