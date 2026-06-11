package com.transit.controller;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.ModelMapping;
import com.transit.service.CurrentUserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/mappings")
@RequiredArgsConstructor
public class ModelMappingController {

    private final ModelMappingMapper modelMappingMapper;
    private final ChannelMapper channelMapper;
    private final CurrentUserService currentUserService;

    @GetMapping
    public Flux<ModelMapping> getAllMappings(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(modelMappingMapper.selectList(null))
                .map(mapping -> {
                    mapping.setChannel(channelMapper.selectById(mapping.getChannelId()));
                    return mapping;
                });
    }

    @PostMapping
    public Mono<ModelMapping> createMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @RequestBody MappingRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ModelMapping mapping = ModelMapping.builder()
                    .publicModelName(request.getPublicModelName())
                    .channelModelName(request.getChannelModelName())
                    .channelId(request.getChannelId())
                    .priority(request.getPriority())
                    .enabled(request.isEnabled())
                    .build();
            modelMappingMapper.insert(mapping);
            return mapping;
        });
    }

    @PutMapping("/{id}")
    public Mono<ModelMapping> updateMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @PathVariable Long id,
                                            @RequestBody MappingRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ModelMapping mapping = modelMappingMapper.selectById(id);
            mapping.setPublicModelName(request.getPublicModelName());
            mapping.setChannelModelName(request.getChannelModelName());
            mapping.setChannelId(request.getChannelId());
            mapping.setPriority(request.getPriority());
            mapping.setEnabled(request.isEnabled());
            modelMappingMapper.updateById(mapping);
            return mapping;
        });
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                    @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> modelMappingMapper.deleteById(id));
    }

    @Data
    public static class MappingRequest {
        private String publicModelName;
        private String channelModelName;
        private Long channelId;
        private int priority;
        private boolean enabled;
    }
}
