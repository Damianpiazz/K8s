package com.ecommerce.cart.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A shopping cart keyed by session {@code cartId}. Item list is synchronized so
 * concurrent requests against the same cart can't corrupt it.
 */
public class Cart {

    private final String id;
    private final List<CartItem> items = Collections.synchronizedList(new ArrayList<>());

    public Cart(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /** Snapshot of the items (safe to serialize). */
    public List<CartItem> getItems() {
        synchronized (items) {
            return List.copyOf(items);
        }
    }

    /** Adds an item, accumulating quantity when the product is already in the cart. */
    public CartItem add(CartItem item) {
        synchronized (items) {
            Optional<CartItem> existing = items.stream()
                    .filter(i -> i.productId() == item.productId())
                    .findFirst();
            if (existing.isPresent()) {
                int idx = items.indexOf(existing.get());
                CartItem merged = new CartItem(item.productId(),
                        existing.get().quantity() + item.quantity(), item.unitPrice());
                items.set(idx, merged);
                return merged;
            }
            items.add(item);
            return item;
        }
    }

    /** Removes a product line; returns true if it was present. */
    public boolean remove(long productId) {
        synchronized (items) {
            return items.removeIf(i -> i.productId() == productId);
        }
    }

    /** Monetary total of all lines. */
    public BigDecimal total() {
        synchronized (items) {
            return items.stream()
                    .map(CartItem::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}