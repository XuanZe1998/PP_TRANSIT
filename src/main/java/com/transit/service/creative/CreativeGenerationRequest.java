package com.transit.service.creative;

import java.util.List;

public record CreativeGenerationRequest(
        String model,
        String mode,
        String prompt,
        String firstFrameUrl,
        String lastFrameUrl,
        List<String> referenceImageUrls,
        String ratio,
        Integer duration,
        String resolution,
        boolean generateAudio
) {
}
