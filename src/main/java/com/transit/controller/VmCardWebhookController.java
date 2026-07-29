package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.service.VmCardWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/webhooks/vmcard")
@RequiredArgsConstructor
public class VmCardWebhookController {
    private final VmCardWebhookService webhookService;

    @PostMapping("/{secret}")
    public Map<String, Object> receive(@PathVariable String secret, @RequestBody JsonNode payload) {
        webhookService.receive(secret, payload);
        return Map.of("code", 0, "msg", "ok");
    }
}
