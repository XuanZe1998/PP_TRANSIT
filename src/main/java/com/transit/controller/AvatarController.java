package com.transit.controller;

import com.transit.service.AvatarStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/public/avatars")
@RequiredArgsConstructor
public class AvatarController {
    private final AvatarStorageService storage;
    @GetMapping("/{fileName:.+}")
    public ResponseEntity<Resource> avatar(@PathVariable String fileName) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic()).body(storage.load(fileName));
    }
}
