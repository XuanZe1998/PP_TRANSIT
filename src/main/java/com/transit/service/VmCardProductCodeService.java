package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.transit.mapper.VmCardProductCodeMapper;
import com.transit.model.VmCardProductCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VmCardProductCodeService {
    private final VmCardProductCodeMapper productCodeMapper;

    @Transactional
    public int sync(String environment, JsonNode productList) {
        if (productList == null || !productList.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "VMCard getProductCode returned an invalid product list");
        }

        String normalizedEnvironment = normalizeEnvironment(environment);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        productCodeMapper.update(null, new LambdaUpdateWrapper<VmCardProductCode>()
                .eq(VmCardProductCode::getEnvironment, normalizedEnvironment)
                .set(VmCardProductCode::getRemainingOpenCardNum, 0)
                .set(VmCardProductCode::getUpdatedAt, now));
        int synchronizedCount = 0;
        for (JsonNode item : productList) {
            String productCode = text(item, "product_code");
            if (productCode.isBlank()) continue;

            VmCardProductCode record = productCodeMapper.selectOne(
                    new LambdaQueryWrapper<VmCardProductCode>()
                            .eq(VmCardProductCode::getEnvironment, normalizedEnvironment)
                            .eq(VmCardProductCode::getProductCode, productCode)
                            .last("LIMIT 1"));
            if (record == null) {
                record = VmCardProductCode.builder()
                        .environment(normalizedEnvironment)
                        .productCode(productCode)
                        .available(true)
                        .createdAt(now)
                        .build();
            }

            record.setBin(text(item, "bin"));
            record.setType(text(item, "type"));
            record.setNetwork(text(item, "network"));
            record.setMedia(text(item, "media"));
            record.setIssuingArea(text(item, "issuing_area"));
            record.setRemainingOpenCardNum(Math.max(0, integer(item, "remaining_open_card_num")));
            record.setUpdatedAt(now);
            if (record.getId() == null) {
                productCodeMapper.insert(record);
            } else {
                productCodeMapper.updateById(record);
            }
            synchronizedCount++;
        }
        return synchronizedCount;
    }

    public Optional<VmCardProductCode> findUsable(String environment, String requestedProductCode) {
        LambdaQueryWrapper<VmCardProductCode> query = new LambdaQueryWrapper<VmCardProductCode>()
                .eq(VmCardProductCode::getEnvironment, normalizeEnvironment(environment))
                .eq(VmCardProductCode::getAvailable, true)
                .gt(VmCardProductCode::getRemainingOpenCardNum, 0);
        if (requestedProductCode != null && !requestedProductCode.isBlank()) {
            query.eq(VmCardProductCode::getProductCode, requestedProductCode.trim());
        }
        query.orderByAsc(VmCardProductCode::getRemainingOpenCardNum)
                .orderByAsc(VmCardProductCode::getProductCode)
                .last("LIMIT 1");
        return Optional.ofNullable(productCodeMapper.selectOne(query));
    }

    @Transactional
    public void decrementRemaining(String environment, String productCode) {
        if (productCode == null || productCode.isBlank()) return;
        productCodeMapper.update(null, new LambdaUpdateWrapper<VmCardProductCode>()
                .eq(VmCardProductCode::getEnvironment, normalizeEnvironment(environment))
                .eq(VmCardProductCode::getProductCode, productCode.trim())
                .gt(VmCardProductCode::getRemainingOpenCardNum, 0)
                .setSql("remaining_open_card_num = remaining_open_card_num - 1")
                .set(VmCardProductCode::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    public List<Map<String, Object>> list(String environment) {
        return productCodeMapper.selectList(new LambdaQueryWrapper<VmCardProductCode>()
                        .eq(VmCardProductCode::getEnvironment, normalizeEnvironment(environment))
                        .orderByDesc(VmCardProductCode::getAvailable)
                        .orderByDesc(VmCardProductCode::getRemainingOpenCardNum)
                        .orderByAsc(VmCardProductCode::getProductCode))
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public Map<String, Object> setAvailability(Long id, String environment, boolean available) {
        VmCardProductCode record = productCodeMapper.selectById(id);
        if (record == null || !normalizeEnvironment(environment).equals(record.getEnvironment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VMCard product code was not found");
        }
        record.setAvailable(available);
        record.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        productCodeMapper.updateById(record);
        return view(record);
    }

    private Map<String, Object> view(VmCardProductCode record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("environment", record.getEnvironment());
        result.put("bin", value(record.getBin()));
        result.put("productCode", value(record.getProductCode()));
        result.put("type", value(record.getType()));
        result.put("network", value(record.getNetwork()));
        result.put("media", value(record.getMedia()));
        result.put("issuingArea", value(record.getIssuingArea()));
        int remaining = record.getRemainingOpenCardNum() == null ? 0 : record.getRemainingOpenCardNum();
        boolean available = Boolean.TRUE.equals(record.getAvailable());
        result.put("remainingOpenCardNum", remaining);
        result.put("available", available);
        result.put("usable", available && remaining > 0);
        result.put("createdAt", record.getCreatedAt());
        result.put("updatedAt", record.getUpdatedAt());
        return result;
    }

    private static String normalizeEnvironment(String environment) {
        return "production".equalsIgnoreCase(environment) ? "production" : "sandbox";
    }

    private static String text(JsonNode item, String field) {
        return item == null ? "" : item.path(field).asText("").trim();
    }

    private static int integer(JsonNode item, String field) {
        if (item == null) return 0;
        JsonNode value = item.path(field);
        if (value.isIntegralNumber()) return value.asInt(0);
        try {
            return Integer.parseInt(value.asText("0").trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
