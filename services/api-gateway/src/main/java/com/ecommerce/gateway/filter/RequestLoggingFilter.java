package com.ecommerce.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Lightweight request logging global filter.
 *
 * <p>Logs one line per request: method, path, HTTP status and elapsed time.
 * Ordered {@code -100} so it runs near the start of the chain and still sees
 * the final status after the exchange completes. Prometheus metrics for the
 * gateway itself come from actuator — this filter is just human-readable logs.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();

        return chain.filter(exchange).doFinally(signalType -> {
            ServerHttpResponse response = exchange.getResponse();
            HttpStatusCode status = response.getStatusCode();
            log.info("{} {} -> {} ({} ms, {})",
                    request.getMethod(),
                    request.getURI().getPath(),
                    status != null ? status.value() : "-",
                    System.currentTimeMillis() - start,
                    signalType.name());
        });
    }

    @Override
    public int getOrder() {
        return -100;
    }
}