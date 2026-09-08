package com.ecommerce.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context smoke test — verifies the Config Server (and the native backend
 * with its bundled YAML files) boots. Requires no external infrastructure.
 */
@SpringBootTest
class ConfigServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}