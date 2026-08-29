package com.transit.controller;

import com.transit.service.CurrentUserService;
import com.transit.service.UpstreamProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/upstream-proxies")
@RequiredArgsConstructor
public class UpstreamProxyAdminController {
    private final CurrentUserService currentUsers;
    private final UpstreamProxyService proxies;

    @GetMapping public List<Map<String, Object>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { currentUsers.requireAdmin(auth); return proxies.list(); }
    @PostMapping public Map<String, Object> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> body) { currentUsers.requireAdmin(auth); return proxies.create(body); }
    @PutMapping("/{id}") public Map<String, Object> update(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id, @RequestBody Map<String, Object> body) { currentUsers.requireAdmin(auth); return proxies.update(id, body); }
    @PostMapping("/{id}/test") public Map<String, Object> test(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable long id) { currentUsers.requireAdmin(auth); return proxies.test(id); }
}
