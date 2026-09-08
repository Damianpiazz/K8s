package com.ecommerce.cart.controller;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import com.ecommerce.cart.service.CartService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Cart REST API (per session). Mounted under /api/cart so the api-gateway
 * forwards /api/cart/** untouched.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    /** Full cart state: items + computed total. */
    @GetMapping("/{cartId}")
    public Cart get(@PathVariable String cartId) {
        return carts.getOrCreate(cartId);
    }

    /** Request body: {"productId": 1, "quantity": 2, "unitPrice": 29.99}. */
    public record AddItemRequest(long productId, int quantity, BigDecimal unitPrice) {
    }

    /** Adds (or merges) a product line. */
    @PostMapping("/{cartId}/items")
    @ResponseStatus(HttpStatus.OK)
    public Cart addItem(@PathVariable String cartId, @RequestBody AddItemRequest request) {
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "quantity must be positive");
        }
        CartItem item = new CartItem(request.productId(), request.quantity(),
                request.unitPrice() != null ? request.unitPrice() : BigDecimal.ZERO);
        return carts.addItem(cartId, item);
    }

    /** Removes a product line; 404 if the cart or product line is unknown. */
    @DeleteMapping("/{cartId}/items/{productId}")
    public ResponseEntity<Void> removeItem(@PathVariable String cartId,
                                           @PathVariable long productId) {
        if (!carts.removeItem(cartId, productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Item " + productId + " not in cart " + cartId);
        }
        return ResponseEntity.noContent().build();
    }

    /** Monetary total of the cart. */
    @GetMapping("/{cartId}/total")
    public Map<String, Object> total(@PathVariable String cartId) {
        Cart cart = carts.getOrCreate(cartId);
        return Map.of(
                "cartId", cart.getId(),
                "total", cart.total(),
                "itemCount", cart.getItems().size()
        );
    }
}