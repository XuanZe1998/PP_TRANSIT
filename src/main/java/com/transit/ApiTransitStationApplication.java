package com.transit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.transit.mapper")
public class ApiTransitStationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiTransitStationApplication.class, normalizeConfigArguments(args));
    }

    /**
     * IntelliJ run configurations commonly pass a local application YAML as a
     * plain program argument. Spring Boot otherwise treats that path as an
     * unrelated non-option argument and silently starts without the secrets in
     * the file. Preserve the convenient launch style while translating only
     * well-known application*.yml names into Spring's explicit config option.
     */
    static String[] normalizeConfigArguments(String[] args) {
        if (args == null || args.length == 0 || Arrays.stream(args).anyMatch(argument ->
                argument.startsWith("--spring.config.location=")
                        || argument.startsWith("--spring.config.additional-location="))) {
            return args == null ? new String[0] : args.clone();
        }

        List<String> normalized = new ArrayList<>();
        List<String> configLocations = new ArrayList<>();
        for (String argument : args) {
            String location = applicationConfigLocation(argument);
            if (location == null) {
                normalized.add(argument);
            } else {
                configLocations.add(location);
            }
        }
        if (!configLocations.isEmpty()) {
            normalized.add("--spring.config.additional-location=" + String.join(",", configLocations));
        }
        return normalized.toArray(String[]::new);
    }

    private static String applicationConfigLocation(String argument) {
        if (argument == null || argument.isBlank() || argument.startsWith("--")) {
            return null;
        }
        try {
            Path path = Path.of(argument).toAbsolutePath().normalize();
            String fileName = path.getFileName() == null
                    ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!fileName.matches("application(?:-[a-z0-9._-]+)?\\.ya?ml")) {
                return null;
            }
            return path.toUri().toString();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }
}
