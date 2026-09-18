package com.ecommerce.cart.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: {@link Cart#total()} must be present in the serialized JSON,
 * because Jackson ignores accessors without a {@code get}/{@code is} prefix
 * unless they carry {@code @JsonProperty}. checkout-svc relies on this field.
 */
class CartSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesTotalWithItems() throws Exception {
        Cart cart = new Cart("cart-1");
        cart.add(new CartItem(5, 2, new BigDecimal("12.34")));

        String json = mapper.writeValueAsString(cart);

        assertThat(json).contains("\"total\"");
        assertThat(json).contains("24.68");
        assertThat(json).contains("\"productId\":5");
    }
}