package com.ecommerce.catalog.controller;

import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Product REST API.
 *
 * <p>Routes are mounted under /api/catalog so the api-gateway can forward
 * /api/catalog/** without rewriting paths.
 */
@RestController
@RequestMapping("/api/catalog/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    /** List all products, optionally filtered by category. */
    @GetMapping
    public List<Product> list(@RequestParam(required = false) String category) {
        if (category == null || category.isBlank()) {
            return products.findAll();
        }
        return products.findByCategoryIgnoreCase(category);
    }

    /** Fetch a single product. */
    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Product " + id + " not found"));
    }

    /** Create a product (client may omit the id). */
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Product create(@RequestBody Product product) {
        product.setId(null); // never trust a client-supplied id
        return products.save(product);
    }

    /** Full update of an existing product. */
    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @RequestBody Product product) {
        if (!products.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Product " + id + " not found");
        }
        product.setId(id);
        return products.save(product);
    }

    /** Delete a product; 204 on success, 404 if unknown. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!products.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Product " + id + " not found");
        }
        products.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}