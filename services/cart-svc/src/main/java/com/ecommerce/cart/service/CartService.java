package com.ecommerce.cart.service;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cart store keyed by session id.
 *
 * <p>Production store: Redis (Azure Cache for Redis). Swap this class for a
 * Redis-backed implementation once {@code spring-boot-starter-data-redis} is
 * enabled (host/port from the {@code REDIS_HOST}/{@code REDIS_PORT} env vars in
 * ecommerce-env-config). The REST contract stays identical.
 */
@Service
public class CartService {

    private final ConcurrentHashMap<String, Cart> carts = new ConcurrentHashMap<>();

    public Cart getOrCreate(String cartId) {
        return carts.computeIfAbsent(cartId, Cart::new);
    }

    public Cart addItem(String cartId, CartItem item) {
        Cart cart = getOrCreate(cartId);
        cart.add(item);
        return cart;
    }

    public boolean removeItem(String cartId, long productId) {
        Cart cart = carts.get(cartId);
        return cart != null && cart.remove(productId);
    }
}