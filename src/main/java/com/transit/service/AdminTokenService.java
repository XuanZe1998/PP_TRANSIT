package com.transit.service;

import com.transit.model.Token;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminTokenService {
    private final ApiKeyService apiKeyService;

    public List<Map<String, Object>> list() {
        return apiKeyService.listAll();
    }

    public Map<String, Object> create(Token request) {
        Long ownerId = request == null ? null : request.getUserId();
        return apiKeyService.issuedView(apiKeyService.issue(ownerId, request));
    }

    public Map<String, Object> update(Long id, Token request) {
        return apiKeyService.update(id, null, request, true);
    }

    public void delete(Long id) {
        apiKeyService.delete(id, null);
    }
}
