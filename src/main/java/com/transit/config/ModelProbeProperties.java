package com.transit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Model-probe sidecar connectivity settings.
 *
 * The model-probe feature runs the BazaarLink LLMprobe-engine in an
 * isolated Node.js sidecar process; the Java backend reaches it over
 * the loopback interface. The feature is fail-closed until explicitly
 * enabled and pointed at a reachable sidecar.
 */
@Data
@Component
@ConfigurationProperties(prefix = "model-probe")
public class ModelProbeProperties {

    /** Master switch. Off by default (fail-closed). */
    private boolean enabled = false;

    /** Sidecar base URL, e.g. http://127.0.0.1:9891 */
    private String sidecarUrl = "http://127.0.0.1:9891";

    /** Per-request timeout to the sidecar, in seconds. */
    private long timeoutSeconds = 900;

    /** Whether admin users may run probes. */
    private boolean adminEnabled = true;

    /** Whether regular users may run probes. */
    private boolean userEnabled = false;
}