package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.service.CurrentUserService;
import com.transit.service.XiaoyunqueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/creative/xiaoyunque")
@RequiredArgsConstructor
public class XiaoyunqueController {
    private final CurrentUserService users;
    private final XiaoyunqueService service;
    @GetMapping("/settings")
    public Object settings(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { return service.settings(users.requireUser(auth)); }
    @PutMapping("/settings")
    public Object save(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String,String> body) { return service.saveKey(users.requireUser(auth), body.get("accessKey")); }
    @DeleteMapping("/settings")
    public Object delete(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { service.deleteKey(users.requireUser(auth)); return Map.of("configured", false); }
    @PostMapping("/balance")
    public Object balance(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { return service.balance(users.requireUser(auth)); }
    @PostMapping("/assets")
    public Object upload(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestParam("file") MultipartFile file) { return service.upload(users.requireUser(auth), file); }
    @PostMapping("/runs")
    public Object submit(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody JsonNode body) { return service.submit(users.requireUser(auth), body); }
    @PostMapping("/runs/{id}/refresh")
    public Object refresh(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable String id) { return service.refresh(users.requireUser(auth), id); }
    @GetMapping("/runs")
    public Object list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestParam(defaultValue="1") int page,
                       @RequestParam(defaultValue="10") int size, @RequestParam(defaultValue="false") boolean all,
                       @RequestParam(defaultValue="") String q) { return service.list(users.requireUser(auth), page, size, all, q); }
    @GetMapping("/runs/{id}/media")
    public Object media(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable String id,
                        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size,
                        @RequestParam(defaultValue="false") boolean all) { return service.media(users.requireUser(auth), id, page, size, all); }
}
