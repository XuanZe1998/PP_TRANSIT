package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.model.ModelProbeTask;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.ModelProbeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin endpoints for the model-probe (LLM identity / quality / security) feature. */
@RestController
@RequestMapping("/admin/api/model-probe")
@RequiredArgsConstructor
public class ModelProbeAdminController {

    private final CurrentUserService currentUserService;
    private final ModelProbeService modelProbeService;

    @PostMapping
    public Map<String, Object> submit(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestBody Map<String, Object> body) {
        User admin = currentUserService.requireAdmin(authHeader);
        modelProbeService.ensureEnabled(true);
        ModelProbeTask task = modelProbeService.submit(admin, body, idempotencyKey);
        return modelProbeService.redact(task);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                   @PathVariable("id") Long id) {
        currentUserService.requireAdmin(authHeader);
        modelProbeService.ensureEnabled(true);
        ModelProbeTask task = modelProbeService.get(id, null);
        return modelProbeService.redact(task);
    }

    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        currentUserService.requireAdmin(authHeader);
        modelProbeService.ensureEnabled(true);
        PageResponse<ModelProbeTask> result = modelProbeService.list(null, page, size);
        PageResponse<Map<String, Object>> view = new PageResponse<>();
        view.setTotal(result.getTotal());
        view.setPage(result.getPage());
        view.setSize(result.getSize());
        view.setItems(result.getItems().stream().map(modelProbeService::redact).toList());
        return view;
    }
}