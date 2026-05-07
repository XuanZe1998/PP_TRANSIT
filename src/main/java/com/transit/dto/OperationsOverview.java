package com.transit.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OperationsOverview {
    private long totalChannels;
    private long enabledChannels;
    private long totalMappings;
    private long totalTokens;
    private long totalUsers;
    private long totalRequests;
    private long successRequests;
    private long failedRequests;
    private long totalConsumedTokens;
    private List<String> activeProviders;
}
