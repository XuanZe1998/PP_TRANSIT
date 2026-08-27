package com.transit.controller;

import com.transit.service.CreativeAssetStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping(CreativeAssetStorage.PUBLIC_PREFIX)
@RequiredArgsConstructor
public class CreativeAssetController {
    private final CreativeAssetStorage storage;

    @GetMapping("/{fileName}")
    public ResponseEntity<?> load(@PathVariable String fileName) {
        CreativeAssetStorage.StoredAsset asset = storage.load(fileName);
        return ResponseEntity.ok().contentType(asset.mediaType())
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic()).body(asset.resource());
    }
}
