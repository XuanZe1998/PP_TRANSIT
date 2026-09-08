package com.transit.controller;

import com.transit.model.Channel;
import com.transit.service.CurrentUserService;
import com.transit.service.NewApiOnboardingService;
import com.transit.service.AdminAuditService;
import com.transit.service.NewApiSyncService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/api/channels/new-api")
@RequiredArgsConstructor
public class NewApiOnboardingController {
    @org.springframework.beans.factory.annotation.Autowired private com.transit.service.GatewaySyncJobs jobs;
    private final CurrentUserService users;
    private final NewApiOnboardingService onboarding;
    private final AdminAuditService audit;
    private final NewApiSyncService sync;
    private final com.transit.service.NewApiCatalogManagementService catalogs;

    @GetMapping("/catalog")
    public java.util.List<Map<String,Object>> catalog(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        users.requireAdmin(authorization);
        return catalogs.directory();
    }

    @PostMapping("/catalog/{id}/sync")
    public Mono<Map<String,Object>> syncCatalog(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                               @PathVariable long id, HttpServletRequest request) {
        var admin = users.requireAdmin(authorization);
        return Mono.fromCallable(() -> {
            Map<String,Object> result = Map.of("jobId",jobs.enqueue(id));
            audit.record(admin, "SYNC_UPSTREAM_CATALOG", "CHANNEL", id, null, result, request.getRemoteAddr());
            return result;
        });
    }

    public record CatalogSettings(boolean enabled) {}
    @PutMapping("/catalog/{id}")
    public void configureCatalog(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                 @PathVariable long id, @RequestBody CatalogSettings settings, HttpServletRequest request) {
        var admin = users.requireAdmin(authorization);
        catalogs.configure(id, settings.enabled());
        audit.record(admin, "CONFIGURE_UPSTREAM_CATALOG", "CHANNEL", id, null,
                Map.of("enabled", settings.enabled()), request.getRemoteAddr());
    }

    public record CatalogImport(boolean preview) {}
    @PostMapping("/catalog/aiapibank/import")
    public Mono<Object> importCatalog(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                     @RequestBody CatalogImport body, HttpServletRequest request) {
        var admin = users.requireAdmin(authorization);
        return Mono.fromCallable(() -> {
            var result = catalogs.importAiApiBank(body.preview());
            if (!body.preview()) audit.record(admin, "IMPORT_UPSTREAM_CATALOG", "CHANNEL", null, null,
                    Map.of("adapter", "aiapibank"), request.getRemoteAddr());
            return result;
        });
    }


    @GetMapping("/sync")
    public java.util.List<Map<String, Object>> syncStatus(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        users.requireAdmin(authorization);
        return sync.status();
    }

    @PostMapping("/{id}/sync")
    public Mono<Map<String, Object>> synchronize(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                @PathVariable long id, HttpServletRequest servletRequest) {
        var admin = users.requireAdmin(authorization);
        return Mono.fromCallable(() -> {
            Map<String,Object> result = Map.of("jobId",jobs.enqueue(id));
            audit.record(admin, "SYNC_NEW_API", "CHANNEL", id, null, result, servletRequest.getRemoteAddr());
            return result;
        });
    }

    @PutMapping("/{id}/sync")
    public void configure(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable long id,
                          @RequestBody NewApiSyncService.Settings settings, HttpServletRequest servletRequest) {
        var admin = users.requireAdmin(authorization);
        sync.configure(id, settings);
        audit.record(admin, "CONFIGURE_NEW_API_SYNC", "CHANNEL", id, null,
                Map.of("syncEnabled", settings.syncEnabled(), "updatePrices", settings.updatePrices()), servletRequest.getRemoteAddr());
    }

    @PostMapping("/preview")
    public Mono<NewApiOnboardingService.Preview> preview(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody NewApiOnboardingService.Request request) {
        users.requireAdmin(authorization);
        return Mono.fromCallable(() -> onboarding.preview(request));
    }

    @PostMapping("/connect")
    public Mono<Channel> connect(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                 @RequestBody NewApiOnboardingService.Request request,
                                 HttpServletRequest servletRequest) {
        var admin = users.requireAdmin(authorization);
        return Mono.fromCallable(() -> {
            Channel channel = onboarding.connect(request);
            audit.record(admin, "CONNECT_NEW_API", "CHANNEL", channel.getId(), null,
                    Map.of("channelName", channel.getName(), "source", "new-api"), servletRequest.getRemoteAddr());
            return channel;
        });
    }
}
