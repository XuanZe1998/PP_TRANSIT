package com.transit.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/** Bounded executor for MVC Mono/Flux adaptation with request-id propagation. */
@Configuration
public class AsyncWebConfig implements WebMvcConfigurer {

    private final ThreadPoolTaskExecutor executor;
    private final long timeoutMs;

    public AsyncWebConfig(
            @Value("${gateway.async.core-pool-size:16}") int corePoolSize,
            @Value("${gateway.async.max-pool-size:128}") int maxPoolSize,
            @Value("${gateway.async.queue-capacity:1000}") int queueCapacity,
            @Value("${gateway.async.timeout-ms:120000}") long timeoutMs) {
        this.timeoutMs = Math.max(1_000, timeoutMs);
        executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gateway-mvc-");
        executor.setCorePoolSize(Math.max(2, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(2, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.setTaskDecorator(task -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (captured == null) MDC.clear(); else MDC.setContextMap(captured);
                try {
                    task.run();
                } finally {
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            };
        });
        executor.initialize();
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(timeoutMs);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
