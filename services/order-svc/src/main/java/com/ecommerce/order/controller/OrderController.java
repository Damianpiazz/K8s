package com.ecommerce.order.controller;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Order REST API. Mounted under /api/orders so the api-gateway forwards
 * /api/orders/** untouched.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orders;

    public OrderController(OrderService orderService, OrderRepository orders) {
        this.orderService = orderService;
        this.orders = orders;
    }

    /**
     * Create an order.
     *
     * <p>Request: {"customerId": "cust-42", "items": [{"productId": 1, "quantity": 2,
     * "price": 29.99}], "total": 59.98}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order create(@RequestBody Order order) {
        order.setStatus(OrderStatus.CREATED);
        try {
            return orderService.create(order);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** List all orders. */
    @GetMapping
    public List<Order> list() {
        return orderService.findAll();
    }

    /** Fetch a single order; 404 if unknown. */
    @GetMapping("/{id}")
    public Order get(@PathVariable Long id) {
        return orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Order " + id + " not found"));
    }

    /** List the orders of one customer. */
    @GetMapping("/customer/{customerId}")
    public List<Order> byCustomer(@PathVariable String customerId) {
        return orderService.findByCustomer(customerId);
    }
}