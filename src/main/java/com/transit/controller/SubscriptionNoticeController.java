package com.transit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.service.AdminReportService;
import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SubscriptionNoticeController {
    private static final String KEY = "subscription.notice";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminReportService settings;
    private final CurrentUserService currentUserService;

    public record Notice(boolean enabled, String title, String content) {}

    @GetMapping("/public/subscription-notice")
    public Mono<Notice> read() {
        return Mono.fromCallable(() -> {
            var values = jdbc.queryForList("SELECT setting_value FROM system_settings WHERE setting_key=?", String.class, KEY);
            if (values.isEmpty()) return new Notice(true, "订阅公告 · 购买须知", "购买前请核对商品说明；付款成功后请在订单详情查看卡密，勿重复付款。");
            return mapper.readValue(values.get(0), Notice.class);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/admin/api/subscription-notice")
    public Mono<Notice> save(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody Notice notice) {
        currentUserService.requireAdmin(auth);
        if (notice.title() == null || notice.content() == null || notice.title().length() > 80 || notice.content().length() > 3000
                || (notice.enabled() && (notice.title().isBlank() || notice.content().isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "公告标题最多80字，内容最多3000字，启用时不能为空");
        }
        return Mono.fromCallable(() -> {
            settings.saveSetting(Map.of("key", KEY, "value", mapper.writeValueAsString(notice), "description", "AI订阅公告"));
            return notice;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
