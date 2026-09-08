package com.ecommerce.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Stock level for one product (the inventory aggregate root).
 *
 * <p>{@code stockLevel} is the physical count on hand; {@code reserved} is the
 * count currently held by open reservations. Available stock =
 * {@code stockLevel - reserved}.
 */
@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private long productId;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "stock_level", nullable = false)
    private int stockLevel;

    @Column(nullable = false)
    private int reserved = 0;

    protected InventoryItem() {
        // JPA requires a no-arg constructor.
    }

    public InventoryItem(long productId, String sku, String name, int stockLevel) {
        this.productId = productId;
        this.sku = sku;
        this.name = name;
        this.stockLevel = stockLevel;
    }

    public Long getId() {
        return id;
    }

    public long getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getStockLevel() {
        return stockLevel;
    }

    public void setStockLevel(int stockLevel) {
        this.stockLevel = stockLevel;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }
}