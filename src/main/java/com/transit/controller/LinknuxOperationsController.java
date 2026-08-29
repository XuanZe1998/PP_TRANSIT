package com.transit.controller;

import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.LinknuxOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LinknuxOperationsController {
    private final CurrentUserService currentUsers;
    private final LinknuxOperationsService operations;

    @GetMapping("/admin/api/operations/overview") public Map<String, Object> overview(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { currentUsers.requireAdmin(auth); return operations.overview(); }
    @GetMapping(value="/admin/api/operations/realtime", produces=MediaType.TEXT_EVENT_STREAM_VALUE) public Flux<Map<String, Object>> realtime(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { currentUsers.requireAdmin(auth); return operations.realtime(); }
    @GetMapping("/public/status") public List<Map<String, Object>> status() { return operations.publicStatus(); }
    @GetMapping("/user/announcements") public List<Map<String, Object>> announcements(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { return operations.announcements(currentUsers.requireUser(auth).getId(), false); }
    @PostMapping("/user/announcements/{id}/read") public Map<String, Object> read(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) { operations.markAnnouncementRead(currentUsers.requireUser(auth).getId(), id); return Map.of("status","READ"); }
    @GetMapping("/admin/api/announcements") public List<Map<String, Object>> adminAnnouncements(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { currentUsers.requireAdmin(auth); return operations.announcements(null, true); }
    @PostMapping("/admin/api/announcements") public Map<String, Object> createAnnouncement(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> body) { User admin=currentUsers.requireAdmin(auth); return operations.createAnnouncement(admin.getId(),body); }
    @GetMapping("/admin/api/backups") public List<Map<String, Object>> backups(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { currentUsers.requireAdmin(auth); return operations.backups(); }
    @PostMapping("/admin/api/backups") public Map<String, Object> backup(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { return operations.requestBackup(currentUsers.requireAdmin(auth).getId()); }
}
