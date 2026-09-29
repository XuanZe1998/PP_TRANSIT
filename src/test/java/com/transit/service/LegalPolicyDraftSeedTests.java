package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegalPolicyDraftSeedTests {
    private JdbcTemplate database() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:policy_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE system_settings(setting_key VARCHAR(160) PRIMARY KEY,setting_value CLOB,description VARCHAR(255),updated_at TIMESTAMP)");
        return jdbc;
    }

    private void put(JdbcTemplate jdbc, String key, String value) {
        jdbc.update("INSERT INTO system_settings(setting_key,setting_value) VALUES (?,?)", key, value);
    }

    private String value(JdbcTemplate jdbc, String key) {
        return jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key=?", String.class, key);
    }

    @Test void seedsAllNineBilingualPoliciesAndKeepsOperatorVersionsAndSwitches() {
        JdbcTemplate jdbc = database();
        put(jdbc, "legal.operator", "Existing operator");
        put(jdbc, "legal.registration", "Individual");
        put(jdbc, "legal.contact_email", "operator@example.com");
        put(jdbc, "legal.terms_version", "2026-10-01");
        put(jdbc, "legal.privacy_version", "2026-10-01");
        put(jdbc, "legal.effective_date", "2026-10-01");
        put(jdbc, "commerce.payments_enabled", "false");
        SchemaRepairService service = new SchemaRepairService(jdbc);
        service.seedBlankLegalDisclosureFields();
        jdbc.update("UPDATE system_settings SET setting_value=NULL WHERE setting_key='legal.privacy'");
        jdbc.update("UPDATE system_settings SET setting_value='   ' WHERE setting_key='legal.refund_en'");
        service.seedBilingualLegalPolicyDrafts();
        service.seedBilingualLegalPolicyDrafts();
        assertThat(LegalPolicyDrafts.documents()).hasSize(18);
        LegalPolicyDrafts.documents().forEach((key, text) -> assertThat(value(jdbc, key)).isEqualTo(text));
        assertThat(value(jdbc, "legal.operator")).isEqualTo("Existing operator");
        assertThat(value(jdbc, "legal.registration")).isEqualTo("Individual");
        assertThat(value(jdbc, "legal.contact_email")).isEqualTo("operator@example.com");
        for (String field : List.of("terms_version", "privacy_version", "effective_date"))
            assertThat(value(jdbc, "legal." + field)).isEqualTo("2026-10-01");
        assertThat(value(jdbc, "commerce.payments_enabled")).isEqualTo("false");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_settings WHERE setting_key='legal.publication_approved'", Integer.class)).isZero();
        var documents = new LegalDocumentService(jdbc).publicDocuments();
        assertThat(documents.get("publication_ready")).isEqualTo(false);
        assertThat(documents.get("payments_enabled")).isEqualTo(false);
        assertThat(documents.get("checkout_ready")).isEqualTo(false);
        assertThat(documents.get("legalReviewRequired")).isEqualTo(true);
    }

    @Test void preservesCustomPolicyAndDoesNotRepopulateAnIntentionalLaterClearing() {
        JdbcTemplate jdbc = database();
        put(jdbc, "legal.terms", "Operator-authored terms");
        put(jdbc, "legal.support", "Custom contact and hours");
        SchemaRepairService service = new SchemaRepairService(jdbc);
        service.seedBlankLegalDisclosureFields();
        service.seedBilingualLegalPolicyDrafts();
        assertThat(value(jdbc, "legal.terms")).isEqualTo("Operator-authored terms");
        assertThat(value(jdbc, "legal.support")).isEqualTo("Custom contact and hours");
        jdbc.update("UPDATE system_settings SET setting_value='' WHERE setting_key='legal.refund'");
        service.seedBilingualLegalPolicyDrafts();
        assertThat(value(jdbc, "legal.refund")).isEmpty();
    }

    @Test void neverChangesAnAlreadyApprovedPublicationEvenWithMissingPolicies() {
        JdbcTemplate jdbc = database();
        put(jdbc, "legal.publication_approved", "TRUE");
        put(jdbc, "commerce.payments_enabled", "true");
        put(jdbc, "legal.support", LegalPolicyDrafts.LEGACY_SUPPORT_TYPO);
        SchemaRepairService service = new SchemaRepairService(jdbc);
        service.seedBlankLegalDisclosureFields();
        service.seedBilingualLegalPolicyDrafts();
        assertThat(value(jdbc, "legal.terms")).isEmpty();
        assertThat(value(jdbc, "legal.support")).isEqualTo(LegalPolicyDrafts.LEGACY_SUPPORT_TYPO);
        assertThat(value(jdbc, "commerce.payments_enabled")).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_settings WHERE setting_key=?", Integer.class, LegalPolicyDrafts.SEED_MARKER)).isZero();
        assertThat(new LegalDocumentService(jdbc).publicDocuments().get("checkout_ready")).isEqualTo(false);
    }

    @Test void correctsOnlyTheExactLegacySupportTypo() {
        JdbcTemplate jdbc = database();
        put(jdbc, "legal.support", LegalPolicyDrafts.LEGACY_SUPPORT_TYPO);
        SchemaRepairService service = new SchemaRepairService(jdbc);
        service.seedBlankLegalDisclosureFields();
        service.seedBilingualLegalPolicyDrafts();
        assertThat(value(jdbc, "legal.support")).isEqualTo(LegalPolicyDrafts.documents().get("legal.support"));
        assertThat(value(jdbc, "legal.support")).doesNotContain("linknux.gmail.com");
    }

    @Test void draftCopyStatesUnverifiedRetentionAndPreservesConsumerRights() {
        var drafts = LegalPolicyDrafts.documents();
        drafts.forEach((key, text) -> assertThat(text).contains("2026-09-29"));
        assertThat(drafts.get("legal.privacy")).contains("当前未确认具体天数", "不能把“API 中转”理解为所有内容均不落盘");
        assertThat(drafts.get("legal.ai_data_en")).contains("does not promise zero retention", "not automatically applicable");
        assertThat(drafts.get("legal.refund_en")).contains("not a blanket no-refunds policy", "express request, consent");
        assertThat(drafts.get("legal.subprocessors_en")).contains("not a complete subprocessor disclosure", "do not invent");
        assertThat(drafts.get("legal.cookies_en")).contains("localStorage", "not an automatically expiring cookie");
        assertThat(drafts.get("legal.security_en")).contains("not certification", "does not authorise testing");
    }
}
