package com.transit.service;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ChannelModelNames {
    private ChannelModelNames() {}
    static List<String> parseModels(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String item : value.split("[,，、\\r\\n]+")) {
            String model = item.trim();
            if (model.isBlank()) continue;
            if (!model.matches("[A-Za-z0-9._:/-]{1,160}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model name is invalid: " + model);
            }
            unique.add(model);
        }
        return List.copyOf(unique);
    }

}
