package com.transit.service.creative;

public record CreativeProviderTaskState(
        String status,
        String videoUrl,
        String thumbnailUrl,
        String lastFrameUrl,
        String errorMessage
) {
}
