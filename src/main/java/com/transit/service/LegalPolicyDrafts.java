package com.transit.service;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned review drafts, not approval, operator identity or a statement of verified providers. */
final class LegalPolicyDrafts {
    static final String SEED_MARKER = "legal.policy_drafts_seeded_2026_09_29";
    static final String LEGACY_SUPPORT_TYPO = "有问题请联系客服：linknux.gmail.com，工作日内回复";
    private static final Map<String, String> DOCUMENTS = load();

    private LegalPolicyDrafts() { }

    static Map<String, String> documents() { return DOCUMENTS; }

    private static Map<String, String> load() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String kind : List.of("terms", "privacy", "refund", "ai_data", "rights", "support",
                "cookies", "security", "subprocessors")) {
            for (String suffix : List.of("", "_en")) {
                String field = kind + suffix;
                try (var input = new ClassPathResource("legal-drafts/" + field + ".txt").getInputStream()) {
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
                    if (text.isBlank()) throw new IllegalStateException("Empty policy draft: " + field);
                    result.put("legal." + field, text);
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot read policy draft: " + field, exception);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
