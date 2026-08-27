package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.service.ModelTaskService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class ModelTaskController {
    private final ModelTaskService tasks;

    @PostMapping
    public Mono<ResponseEntity<JsonNode>> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return tasks.create(auth, request.getRemoteAddr(), body, key)
                .map(value -> ResponseEntity.status(201).body(value));
    }

    @GetMapping("/{taskId}")
    public Mono<ResponseEntity<JsonNode>> get(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @PathVariable String taskId, HttpServletRequest request) {
        return tasks.get(auth, request.getRemoteAddr(), taskId).map(ResponseEntity::ok);
    }
}
