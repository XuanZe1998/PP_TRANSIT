package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProviderCatalogItem {
    private String provider;
    private String providerType;
    private String headline;
    private String endpointStyle;
    private String recommendedBaseUrl;
    private List<String> modelFamilies;
    private List<String> highlights;
}
