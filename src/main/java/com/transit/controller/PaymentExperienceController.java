package com.transit.controller;
import com.transit.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController
@RequiredArgsConstructor
public class PaymentExperienceController {
    private final PaymentExperienceService content;
    private final CurrentUserService users;
    private final AdminAuditService audit;
    @GetMapping("/public/payment-content")
    public ResponseEntity<Map<String,String>> read() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(content.read()); }
    @PutMapping("/admin/api/payment-content")
    public Map<String,String> save(@RequestHeader("Authorization") String auth, @RequestBody Map<String,String> input) {
        var admin = users.requireAdmin(auth);
        var before = content.read();
        var saved = content.save(input);
        audit.record(admin, "UPDATE_PAYMENT_CONTENT", "SYSTEM_SETTING", null, before, saved, null);
        return saved;
    }
}
