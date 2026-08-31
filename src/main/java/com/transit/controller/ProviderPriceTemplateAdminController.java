package com.transit.controller;

import com.transit.service.CurrentUserService;
import com.transit.service.ProviderPriceTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/provider-price-templates")
@RequiredArgsConstructor
public class ProviderPriceTemplateAdminController {
    private final CurrentUserService users;
    private final ProviderPriceTemplateService templates;
    private void admin(String auth) { users.requireAdmin(auth); }

    @GetMapping public List<Map<String, Object>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestParam(required = false) String platform) { admin(auth); return templates.list(platform); }
    @PostMapping public Map<String, Object> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> body) { admin(auth); return templates.create(body); }
    @PutMapping("/{id}") public Map<String, Object> update(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id, @RequestBody Map<String, Object> body) { admin(auth); return templates.update(id, body); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id) { admin(auth); templates.delete(id); return Map.of("deleted", true); }
    @PostMapping("/preview") public List<Map<String, Object>> preview(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Preview body) { admin(auth); return templates.preview(body.platform(), body.models(), body.templateId()); }
    public record Preview(String platform, List<String> models, Long templateId) {}
}
