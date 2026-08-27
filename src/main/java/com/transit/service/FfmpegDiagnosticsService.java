package com.transit.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class FfmpegDiagnosticsService {
    private static final Path WINDOWS_INSTALL = Path.of("D:/Tools/ffmpeg/bin/ffmpeg.exe");

    public String executable() {
        if (Files.isRegularFile(WINDOWS_INSTALL)) return WINDOWS_INSTALL.toString();
        return "ffmpeg";
    }

    public boolean available() { return Boolean.TRUE.equals(diagnostics().get("available")); }

    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        String executable = executable();
        result.put("path", executable);
        CommandResult version = run(List.of(executable, "-version"), 8);
        result.put("available", version.ok());
        result.put("version", firstLine(version.output()));
        if (!version.ok()) {
            result.put("libx264", false); result.put("aac", false);
            result.put("xfade", false); result.put("acrossfade", false);
            result.put("ready", false); result.put("error", version.output());
            return result;
        }
        CommandResult encoders = run(List.of(executable, "-hide_banner", "-encoders"), 10);
        CommandResult filters = run(List.of(executable, "-hide_banner", "-filters"), 10);
        boolean x264 = encoders.output().contains("libx264");
        boolean aac = encoders.output().matches("(?s).*\\bAAC\\b.*") || encoders.output().contains(" aac ");
        boolean xfade = filters.output().contains(" xfade ");
        boolean acrossfade = filters.output().contains(" acrossfade ");
        result.put("libx264", x264); result.put("aac", aac);
        result.put("xfade", xfade); result.put("acrossfade", acrossfade);
        result.put("ready", x264 && aac && xfade && acrossfade);
        return result;
    }

    private CommandResult run(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(false, "命令执行超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue() == 0, output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new CommandResult(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private String firstLine(String output) {
        if (output == null || output.isBlank()) return "";
        return output.lines().findFirst().orElse("");
    }

    private record CommandResult(boolean ok, String output) { }
}
