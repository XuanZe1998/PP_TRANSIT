package com.transit.controller;

import com.transit.model.Channel;
import com.transit.service.AdminChannelService;
import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final AdminChannelService channelService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public Flux<Channel> getAllChannels(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(channelService.list());
    }

    @PostMapping
    public Mono<Channel> createChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @RequestBody Channel channel) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> channelService.create(channel));
    }

    @PutMapping("/{id}")
    public Mono<Channel> updateChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @PathVariable Long id,
                                       @RequestBody Channel channel) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> channelService.update(id, channel));
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                    @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> channelService.delete(id));
    }
}
