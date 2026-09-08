package com.ecommerce.bff.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient beans bound to the catalog/cart services.
 *
 * <p>Both clients get a 2s response/connect timeout so the storefront never
 * hangs behind a slow or missing backend — the BFF service turns timeouts into
 * degraded payloads (see BffService).
 */
@Configuration
public class BffWebClientConfig {

    @Bean
    WebClient catalogClient(
            @Value("${bff.catalog.base-url}") String baseUrl,
            @Value("${bff.timeout-ms}") long timeoutMs) {
        return build(baseUrl, timeoutMs);
    }

    @Bean
    WebClient cartClient(
            @Value("${bff.cart.base-url}") String baseUrl,
            @Value("${bff.timeout-ms}") long timeoutMs) {
        return build(baseUrl, timeoutMs);
    }

    private WebClient build(String baseUrl, long timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}