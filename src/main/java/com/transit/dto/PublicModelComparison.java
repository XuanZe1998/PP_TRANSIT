package com.transit.dto;

import java.util.List;

public record PublicModelComparison(String comparisonKey, String displayName,
                                    int comparableCount, List<PublicModel> offers) { }
