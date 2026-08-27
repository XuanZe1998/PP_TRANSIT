package com.transit.service.creative;

import com.fasterxml.jackson.databind.JsonNode;

public interface CreativeTextProvider {
    boolean isConfigured();
    String defaultModel();
    JsonNode generateScript(String sourceText, String title, int targetDuration, String ratio,
                            String style, String language, String model, CreativeProviderAccess access);
}
