package com.transit.service.creative;

public interface CreativeImageProvider {
    boolean isImageConfigured();
    String defaultImageModel();
    GeneratedImage generate(String prompt, String model, CreativeProviderAccess access);
    record GeneratedImage(String url, byte[] bytes) { }
}
