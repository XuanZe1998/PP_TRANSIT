package com.transit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PublicUpstream {
    private String code;
    private String name;
    private String badgeText;
    private String badgeColor;
}
