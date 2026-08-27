package com.transit.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public enum VmCardOperation {
    ACCOUNT_BALANCE("accountBalance", "获取账号余额", "/getAccountBalance", false, "{}"),
    PRODUCT_CODES("productCodes", "获取卡产品码", "/getProductCode", false, "{}"),
    CREATE_CARD("createCard", "申请卡", "/createCard", true, """
            {
              "product_code": "",
              "first_name": "Test",
              "last_name": "User",
              "amount": 20,
              "single_limit": 0,
              "day_limit": 0,
              "month_limit": 0,
              "label": "sandbox-test",
              "card_address": {
                "address_line_one": "123 Test Street",
                "address_line_two": "",
                "city": "New York",
                "state": "NY",
                "country": "US",
                "post_code": "10001"
              },
              "area_code": "+1",
              "mobile": "2025550100",
              "email": "sandbox.test@example.com"
            }
            """),
    CARD_DETAIL("cardDetail", "卡详情", "/cardDetail", false, "{\"card_id\":\"\"}"),
    UPDATE_CARD_LIMIT("updateCardLimit", "修改卡限额（仅适用额度卡）", "/updateCardLimit", true,
            "{\"card_id\":\"\",\"single_limit\":100,\"day_limit\":500,\"amount\":1000}"),
    FREEZE_CARD("freezeCard", "冻结/解冻卡", "/freezeCard", true,
            "{\"card_id\":\"\",\"status\":\"CANCELLED\"}"),
    RECHARGE_CARD("rechargeCard", "卡充值（仅适用储值卡）", "/rechargeCard", true,
            "{\"card_id\":\"\",\"amount\":10}"),
    REFUND_CARD("refundCard", "卡退款（仅适用储值卡）", "/refundCard", true,
            "{\"card_id\":\"\",\"amount\":10}"),
    CARD_TRANSACTIONS("cardTransactions", "交易记录", "/cardTransaction", false,
            "{\"page\":1,\"page_size\":10}"),
    DELETE_CARD("deleteCard", "删卡", "/deleteCard", true, "{\"card_id\":\"\"}"),
    CARD_LIST("cardList", "卡列表", "/getCardList", false,
            "{\"page\":1,\"page_size\":10}"),
    CARD_FLOW("cardFlow", "获取卡流水", "/getCardFlow", false,
            "{\"page\":1,\"page_size\":10}");

    private final String id;
    private final String label;
    private final String path;
    private final boolean mutating;
    private final String sampleJson;

    VmCardOperation(String id, String label, String path, boolean mutating, String sampleJson) {
        this.id = id;
        this.label = label;
        this.path = path;
        this.mutating = mutating;
        this.sampleJson = sampleJson.trim();
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String path() {
        return path;
    }

    public boolean mutating() {
        return mutating;
    }

    public String sampleJson() {
        return sampleJson;
    }

    public static VmCardOperation fromId(String id) {
        return Arrays.stream(values())
                .filter(operation -> operation.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unsupported VMCard operation"));
    }

    public static List<Map<String, Object>> metadata() {
        return Arrays.stream(values()).map(operation -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", operation.id);
            row.put("label", operation.label);
            row.put("method", "POST");
            row.put("path", operation.path);
            row.put("mutating", operation.mutating);
            row.put("sampleJson", operation.sampleJson);
            return row;
        }).toList();
    }

    public boolean requiresCardId() {
        return switch (this) {
            case CARD_DETAIL, UPDATE_CARD_LIMIT, FREEZE_CARD, RECHARGE_CARD, REFUND_CARD, DELETE_CARD -> true;
            default -> false;
        };
    }
}
