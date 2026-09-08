package com.ecommerce.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server.
 *
 * <p>Serves centralised, versioned configuration to every other service on
 * port 8888. Points at the native filesystem backend ({@code classpath:/config}),
 * so it works out of the box with zero infrastructure — a Git or Azure Blob
 * backend can be swapped in later without touching the clients.
 */
@EnableConfigServer
@SpringBootApplication
public class ConfigServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServiceApplication.class, args);
    }
}