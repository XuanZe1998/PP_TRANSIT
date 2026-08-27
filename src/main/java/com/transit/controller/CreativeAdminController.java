package com.transit.controller;

import com.transit.model.User;
import com.transit.service.CreativeAssetStorage;
import com.transit.service.CreativePlatformConfigService;
import com.transit.service.CurrentUserService;
import com.transit.service.FfmpegDiagnosticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/creative")
@RequiredArgsConstructor
public class CreativeAdminController {
    private final CurrentUserService currentUserService;
    private final CreativePlatformConfigService configs;
    private final CreativeAssetStorage storage;
    private final FfmpegDiagnosticsService ffmpeg;

    @GetMapping("/settings") public Map<String, Object> settings(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { admin(auth); return configs.settings(); }
    @PutMapping("/settings") public Map<String, Object> settings(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> request) { return configs.updateSettings(admin(auth), request); }
    @GetMapping("/connections") public List<Map<String, Object>> connections(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { admin(auth); return configs.connections(); }
    @PostMapping("/connections") public Map<String, Object> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> request) { return configs.createConnection(admin(auth), request); }
    @PutMapping("/connections/{id}") public Map<String, Object> update(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> request) { return configs.updateConnection(admin(auth), id, request); }
    @DeleteMapping("/connections/{id}") public Map<String, Object> delete(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) { configs.deleteConnection(admin(auth), id); return Map.of("deleted", true); }
    @PostMapping("/connections/{id}/test") public Map<String, Object> testConnection(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) { admin(auth); return configs.testConnection(id); }
    @GetMapping("/storage") public Map<String, Object> storage(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { admin(auth); return configs.storageView(); }
    @PutMapping("/storage") public Map<String, Object> storage(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> request) { return configs.updateStorage(admin(auth), request); }
    @PostMapping("/storage/test") public Map<String, Object> testStorage(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { admin(auth); return storage.testConnection(); }
    @GetMapping("/diagnostics") public Map<String, Object> diagnostics(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        admin(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ffmpeg", ffmpeg.diagnostics());
        result.put("storage", storage.diagnostics());
        for (String capability : CreativePlatformConfigService.CAPABILITIES) {
            result.put(capability.toLowerCase(), Map.of("configured", configs.platformAccess(capability, false) != null));
        }
        result.put("encryptionConfigured", configs.encryptionConfigured());
        return result;
    }
    private User admin(String auth) { return currentUserService.requireAdmin(auth); }
}
