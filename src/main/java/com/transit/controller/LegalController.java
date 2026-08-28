package com.transit.controller;

import com.transit.model.User;
import com.transit.service.ClientIpResolver;
import com.transit.service.CurrentUserService;
import com.transit.service.LegalDocumentService;
import com.transit.service.LoginIpService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LegalController {
    private final LegalDocumentService legal;
    private final CurrentUserService users;
    private final ClientIpResolver clientIps;
    private final LoginIpService loginIps;

    @GetMapping("/public/legal")
    public Map<String,Object> documents() { return legal.publicDocuments(); }

    @PostMapping("/user/legal/accept")
    public Map<String,Object> accept(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                     @RequestBody Map<String,Object> body, HttpServletRequest request) {
        User user = users.requireUser(auth);
        String ip = clientIps.resolve(request);
        legal.accept(user.getId(), String.valueOf(body.get("termsVersion")),
                String.valueOf(body.get("privacyVersion")), loginIps.digest(ip));
        return Map.of("accepted", true);
    }
}
