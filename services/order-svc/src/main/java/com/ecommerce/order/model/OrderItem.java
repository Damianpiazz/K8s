package com.ecommerce.order.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * One line of an order (embeddable — stored in the {@code order_items} table).
 */
@Embeddable
public class OrderItem {

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    protected OrderItem() {
        // JPA requires a no-arg constructor.
    }

    /**
     * Explicit Jackson creator — needed because there is no setter-based path
     * (the no-arg constructor exists only for JPA).
     */
    @JsonCreator
    public OrderItem(@JsonProperty("productId") long productId,
                     @JsonProperty("quantity") int quantity,
                     @JsonProperty("price") BigDecimal price) {
        this.productId = productId;
        this.quantity = quantity;
        this.price = price;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    /** Line total: quantity × price (null price treated as zero). */
    public BigDecimal lineTotal() {
        return (price != null ? price : BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(quantity));
    }
}