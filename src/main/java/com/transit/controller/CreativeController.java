package com.transit.controller;

import com.transit.model.User;
import com.transit.service.CreativeProviderConfigService;
import com.transit.service.CreativeTaskService;
import com.transit.service.CurrentUserService;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/creative")
@RequiredArgsConstructor
public class CreativeController {
    private final CreativeTaskService creativeTaskService;
    private final CreativeProviderConfigService creativeProviderConfigService;
    private final CurrentUserService currentUserService;

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return creativeTaskService.catalog();
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return creativeTaskService.templates();
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return creativeTaskService.list(user);
    }

    @PostMapping("/tasks")
    public Map<String, Object> submit(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                      @RequestBody Map<String, Object> request) {
        User user = currentUserService.requireUser(authHeader);
        return creativeTaskService.submit(user, request);
    }

    @PostMapping("/tasks/{id}/refresh")
    public Map<String, Object> refresh(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return creativeTaskService.refresh(user, id);
    }

    @PostMapping("/prompt/assist")
    public Map<String, Object> promptAssist(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                             @RequestBody Map<String, Object> request) {
        currentUserService.requireUser(authHeader);
        return creativeTaskService.promptAssist(request);
    }

    @GetMapping("/connections")
    public List<Map<String, Object>> connections(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return creativeProviderConfigService.list(user);
    }

    @PostMapping("/connections")
    public Map<String, Object> createConnection(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestBody Map<String, Object> request) {
        User user = currentUserService.requireUser(authHeader);
        return creativeProviderConfigService.create(user, request);
    }

    @PutMapping("/connections/{id}")
    public Map<String, Object> updateConnection(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @PathVariable Long id,
                                                 @RequestBody Map<String, Object> request) {
        User user = currentUserService.requireUser(authHeader);
        return creativeProviderConfigService.update(user, id, request);
    }

    @DeleteMapping("/connections/{id}")
    public Map<String, Object> deleteConnection(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        creativeProviderConfigService.delete(user, id);
        return Map.of("deleted", true);
    }

    @PostMapping("/connections/{id}/test")
    public Map<String, Object> testConnection(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                               @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return creativeProviderConfigService.test(user, id);
    }
}
