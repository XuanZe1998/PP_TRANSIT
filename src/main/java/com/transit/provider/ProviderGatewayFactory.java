package com.transit.provider;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProviderGatewayFactory {

    private final List<ProviderGateway> gateways;

    public ProviderGatewayFactory(List<ProviderGateway> gateways) {
        this.gateways = gateways;
    }

    public ProviderGateway resolve(String providerType) {
        return gateways.stream()
                .filter(gateway -> gateway.supports(providerType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider type: " + providerType));
    }
}
