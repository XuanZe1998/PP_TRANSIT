package com.transit.config;

/**
 * CORS is configured once in {@link SecurityConfig}. Keeping a second,
 * wildcard WebFlux mapping here previously bypassed the origin allow-list.
 */
final class CorsConfig {
    private CorsConfig() {
    }
}
