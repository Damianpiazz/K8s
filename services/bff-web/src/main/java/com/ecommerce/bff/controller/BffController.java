package com.ecommerce.bff.controller;

import com.ecommerce.bff.model.CartView;
import com.ecommerce.bff.model.HomeResponse;
import com.ecommerce.bff.service.BffService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Storefront BFF REST API. Mounted under /api/bff so the api-gateway forwards
 * /api/bff/** untouched.
 */
@RestController
@RequestMapping("/api/bff")
public class BffController {

    private final BffService service;

    public BffController(BffService service) {
        this.service = service;
    }

    /** Storefront homepage view: products + hero. */
    @GetMapping("/home")
    public Mono<HomeResponse> home() {
        return service.home();
    }

    /** Cart view joined with product details. */
    @GetMapping("/cart/{cartId}")
    public Mono<CartView> cart(@PathVariable String cartId) {
        return service.cart(cartId);
    }
}