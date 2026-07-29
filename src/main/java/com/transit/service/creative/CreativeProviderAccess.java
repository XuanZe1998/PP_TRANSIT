package com.transit.service.creative;

import java.util.List;

/**
 * Decrypted provider access is created only for the duration of one backend
 * request. It is never serialized to the browser or stored on a creative task.
 */
public record CreativeProviderAccess(
        String providerKey,
        String displayName,
        String baseUrl,
        String apiKey,
        String defaultModel,
        List<String> models
) {
}
