CREATE TABLE IF NOT EXISTS sensitive_words (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    term VARCHAR(255) NOT NULL,
    category VARCHAR(80) NOT NULL,
    match_mode VARCHAR(24) NOT NULL DEFAULT 'CONTAINS',
    action VARCHAR(24) NOT NULL DEFAULT 'BLOCK',
    scope_type VARCHAR(24) NOT NULL DEFAULT 'GLOBAL',
    scope_id BIGINT NULL,
    note VARCHAR(500) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sensitive_words_enabled_scope
    ON sensitive_words(enabled, scope_type, scope_id);

CREATE TABLE IF NOT EXISTS security_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(80) NOT NULL,
    sensitive_word_id BIGINT NOT NULL,
    category VARCHAR(80) NOT NULL,
    matched_term VARCHAR(255) NOT NULL,
    action VARCHAR(24) NOT NULL,
    organization_id BIGINT NULL,
    user_id BIGINT NULL,
    token_id BIGINT NULL,
    model VARCHAR(180) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_security_events_created ON security_events(created_at);
CREATE INDEX idx_security_events_actor ON security_events(organization_id, user_id, token_id);

INSERT INTO sensitive_words(term, category, match_mode, action, scope_type, note, enabled)
SELECT '购买盗刷卡', '违法交易', 'CONTAINS', 'BLOCK', 'GLOBAL', '模板词条，确认后再启用', FALSE
WHERE NOT EXISTS (SELECT 1 FROM sensitive_words WHERE term='购买盗刷卡' AND scope_type='GLOBAL');

INSERT INTO sensitive_words(term, category, match_mode, action, scope_type, note, enabled)
SELECT '泄露API密钥', '凭证泄露', 'CONTAINS', 'WARN', 'GLOBAL', '模板词条，确认后再启用', FALSE
WHERE NOT EXISTS (SELECT 1 FROM sensitive_words WHERE term='泄露API密钥' AND scope_type='GLOBAL');

INSERT INTO sensitive_words(term, category, match_mode, action, scope_type, note, enabled)
SELECT '批量出售身份证', '隐私与个人信息', 'CONTAINS', 'REVIEW', 'GLOBAL', '模板词条，确认后再启用', FALSE
WHERE NOT EXISTS (SELECT 1 FROM sensitive_words WHERE term='批量出售身份证' AND scope_type='GLOBAL');

INSERT INTO sensitive_words(term, category, match_mode, action, scope_type, note, enabled)
SELECT '实施暴力恐吓', '暴力与威胁', 'CONTAINS', 'REVIEW', 'GLOBAL', '模板词条，确认后再启用', FALSE
WHERE NOT EXISTS (SELECT 1 FROM sensitive_words WHERE term='实施暴力恐吓' AND scope_type='GLOBAL');

INSERT INTO sensitive_words(term, category, match_mode, action, scope_type, note, enabled)
SELECT '未成年人色情内容', '色情及未成年人', 'CONTAINS', 'BLOCK', 'GLOBAL', '模板词条，确认后再启用', FALSE
WHERE NOT EXISTS (SELECT 1 FROM sensitive_words WHERE term='未成年人色情内容' AND scope_type='GLOBAL');

INSERT INTO sensitive_words(term, category, match_mode, action, scope_type, note, enabled)
SELECT '自定义业务禁用词', '业务自定义', 'CONTAINS', 'WARN', 'GLOBAL', '示例模板，请修改后启用', FALSE
WHERE NOT EXISTS (SELECT 1 FROM sensitive_words WHERE term='自定义业务禁用词' AND scope_type='GLOBAL');
