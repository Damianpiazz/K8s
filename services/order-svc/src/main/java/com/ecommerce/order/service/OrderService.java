package com.ecommerce.order.service;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Order business rules: validation (max items per order, non-empty items) and
 * CRUD delegation to the JPA repository.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final int maxItemsPerOrder;

    public OrderService(OrderRepository orders,
                        @Value("${order.max-items-per-order:50}") int maxItemsPerOrder) {
        this.orders = orders;
        this.maxItemsPerOrder = maxItemsPerOrder;
    }

    @Transactional
    public Order create(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one item");
        }
        if (order.getItems().size() > maxItemsPerOrder) {
            throw new IllegalArgumentException(
                    "order exceeds max items per order (" + maxItemsPerOrder + ")");
        }
        for (OrderItem item : order.getItems()) {
            if (item.getQuantity() <= 0) {
                throw new IllegalArgumentException("item quantity must be positive");
            }
        }
        log.info("Creating order for customer {}", order.getCustomerId());
        return orders.save(order);
    }

    public List<Order> findAll() {
        return orders.findAll();
    }

    public List<Order> findByCustomer(String customerId) {
        return orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}