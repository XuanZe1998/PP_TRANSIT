package com.transit.controller;

import com.transit.model.ProviderCredential;
import com.transit.service.CurrentUserService;
import com.transit.service.ProviderAccountAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/provider-accounts")
@RequiredArgsConstructor
public class ProviderAccountAdminController {
    private final CurrentUserService currentUsers;
    private final ProviderAccountAdminService accounts;
    private void admin(String auth) { currentUsers.requireAdmin(auth); }

    @GetMapping public List<Map<String, Object>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestParam(required = false) Long channelId) { admin(auth); return accounts.list(channelId); }
    @GetMapping("/status") public Map<String, Object> status(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { admin(auth); return accounts.status(); }
    @PostMapping public ProviderCredential create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> body) { admin(auth); return accounts.create(body); }
    @PostMapping("/bulk-import") public List<ProviderCredential> bulk(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> body) { admin(auth); return accounts.bulkImport(body); }
    @PostMapping("/{id}/pause") public Map<String, Object> pause(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id) { admin(auth); accounts.setPaused(id, true); return Map.of("status", "PAUSED"); }
    @PostMapping("/{id}/resume") public Map<String, Object> resume(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id) { admin(auth); accounts.setPaused(id, false); return Map.of("status", "ACTIVE"); }
    @PostMapping("/{id}/routes") public Map<String, Object> bind(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id, @RequestBody Map<String, Object> body) { admin(auth); accounts.bindRoute(id, body); return Map.of("status", "BOUND"); }
    @PostMapping("/{id}/quota-snapshots") public Map<String, Object> quota(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id, @RequestBody Map<String, Object> body) { admin(auth); accounts.snapshotQuota(id, body); return Map.of("status", "CAPTURED"); }
    @PostMapping("/{id}/test") public Map<String, Object> test(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id) { admin(auth); return accounts.test(id); }
    @PostMapping("/oauth/{platform}/authorize") public Map<String, Object> oauth(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable String platform) { admin(auth); return accounts.oauthAuthorization(platform); }
}
