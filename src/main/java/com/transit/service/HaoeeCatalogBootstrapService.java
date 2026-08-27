package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/** Versioned, reviewed Haoee catalog. Runtime documentation scraping is deliberately avoided. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HaoeeCatalogBootstrapService {
    private final JdbcTemplate jdbcTemplate;
    private final ChannelSecretService secrets;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final ProviderModelVerificationService verificationService;

    @Value("${haoee.enabled:false}") private boolean enabled;
    @Value("${haoee.base-url:https://maas.haoee.com}") private String baseUrl;
    @Value("${haoee.api-key:}") private String apiKey;
    @Value("${haoee.verify-on-startup:false}") private boolean verifyOnStartup;
    @Value("${haoee.activate-models:false}") private boolean legacyActivateModels;
    @Value("${haoee.startup-verification-limit:10}") private int startupVerificationLimit;
    @Value("${model-catalog.manual-verification-only:true}") private boolean manualVerificationOnly;

    @Bean
    @Order(3)
    ApplicationRunner haoeeCatalogRunner() {
        return args -> {
            if (!enabled || apiKey == null || apiKey.isBlank()) return;
            if (!secrets.isConfigured()) throw new IllegalStateException(
                    "Haoee is enabled but provider credential encryption is not configured");
            long channelId = ensureChannel();
            ensureCredential(channelId);
            int total = providerModelCatalogService.synchronizeHaoee(channelId);
            log.info("Haoee catalog synchronized from versioned manifest: {} models", total);
            if (!manualVerificationOnly && (verifyOnStartup || legacyActivateModels)) {
                List<Long> queued = verificationService.queue("haoee", startupVerificationLimit, false);
                verificationService.verifyQueuedAsync(queued, false);
                log.info("Queued {} low-cost Haoee catalog models for startup verification", queued.size());
            }
        };
    }

    private long ensureChannel() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM channels WHERE source_code='haoee' ORDER BY id LIMIT 1", Long.class);
        if (!ids.isEmpty()) return ids.get(0);
        jdbcTemplate.update("""
                INSERT INTO channels
                (name,type,source_code,source_name,protocol_type,base_url,api_key,models,enabled,
                 group_name,weight,health_status,created_at)
                VALUES ('好易智算 MaaS','haoee','haoee','好易智算','multi',?,?,NULL,TRUE,
                        'haoee',100,'UNTESTED',?)
                """, baseUrl.replaceAll("/+$", ""), secrets.encrypt(apiKey), LocalDateTime.now());
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM channels WHERE source_code='haoee'", Long.class);
    }

    private void ensureCredential(long channelId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_credentials WHERE channel_id=?", Integer.class, channelId);
        if (count != null && count > 0) return;
        jdbcTemplate.update("""
                INSERT INTO provider_credentials
                (channel_id,name,encrypted_secret,secret_preview,priority,weight,enabled,health_status,created_at,updated_at)
                VALUES (?,'Haoee Primary',?,?,100,100,TRUE,'UNTESTED',?,?)
                """, channelId, secrets.encrypt(apiKey), mask(apiKey), LocalDateTime.now(), LocalDateTime.now());
    }

    private String mask(String key) {
        if (key.length() < 10) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

}
