package com.transit.service.creative;

import java.util.Map;

/**
 * Provider boundary for asynchronous creative/video APIs. New relay stations
 * only need to implement this interface; task storage and the studio UI stay unchanged.
 */
public interface CreativeVideoProvider {
    String key();

    boolean isConfigured();

    Map<String, Object> catalog();

    CreativeProviderSubmission submit(CreativeGenerationRequest request);

    CreativeProviderTaskState fetch(String providerTaskId);

    default CreativeProviderSubmission submit(CreativeGenerationRequest request, CreativeProviderAccess access) {
        return submit(request);
    }

    default CreativeProviderTaskState fetch(String providerTaskId, CreativeProviderAccess access) {
        return fetch(providerTaskId);
    }

    default Map<String, Object> testConnection(CreativeProviderAccess access) {
        throw new UnsupportedOperationException("Provider connection testing is not supported");
    }
}
