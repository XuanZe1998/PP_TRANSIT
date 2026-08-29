package com.transit.controller;

import com.transit.model.User;
import com.transit.service.AgentDistributionService;
import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AgentController {
    private final CurrentUserService currentUsers;
    private final AgentDistributionService agents;

    @GetMapping("/user/agent")
    public Map<String, Object> summary(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        return agents.summary(currentUsers.requireUser(auth).getId());
    }

    @PostMapping("/user/agent/apply")
    public Map<String, Object> apply(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                     @RequestBody Map<String, Object> body) {
        return agents.apply(currentUsers.requireUser(auth).getId(), number(body.get("customerRebateBps")));
    }

    @PostMapping("/user/agent/transfer")
    public Map<String, Object> transfer(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                        @RequestBody Map<String, Object> body) {
        return agents.transferToBalance(currentUsers.requireUser(auth).getId(), longNumber(body.get("amount")), String.valueOf(body.get("eventKey")));
    }

    @PostMapping("/user/agent/withdrawals")
    public Map<String, Object> withdraw(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                        @RequestBody Map<String, Object> body) {
        return agents.requestWithdrawal(currentUsers.requireUser(auth).getId(), longNumber(body.get("amount")),
                String.valueOf(body.getOrDefault("destinationType", "MANUAL")), (String) body.get("destinationEncrypted"));
    }

    @GetMapping("/admin/api/agents")
    public List<Map<String, Object>> adminList(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        currentUsers.requireAdmin(auth); return agents.adminAgents();
    }

    @GetMapping("/admin/api/agents/service-costs")
    public List<Map<String, Object>> serviceCosts(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        currentUsers.requireAdmin(auth); return agents.serviceCosts();
    }

    @GetMapping("/admin/api/agents/withdrawals")
    public List<Map<String, Object>> withdrawals(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        currentUsers.requireAdmin(auth); return agents.adminWithdrawals();
    }

    @PutMapping("/admin/api/agents/service-costs/{id}")
    public Map<String, Object> serviceCost(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id,
                                           @RequestBody Map<String, Object> body) {
        currentUsers.requireAdmin(auth);
        Long cost = body.get("costCents") instanceof Number n ? n.longValue() : null;
        return agents.updateServiceCost(id, cost, number(body.getOrDefault("refundWindowDays", 7)));
    }

    @PostMapping("/admin/api/agents/{id}/review")
    public Map<String, Object> review(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        User admin = currentUsers.requireAdmin(auth);
        return agents.reviewAgent(admin.getId(), id, String.valueOf(body.get("status")), (String) body.get("tierCode"));
    }

    @PostMapping("/admin/api/agents/withdrawals/{id}/review")
    public Map<String, Object> reviewWithdrawal(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        User admin = currentUsers.requireAdmin(auth);
        return agents.reviewWithdrawal(admin.getId(), id, String.valueOf(body.get("status")), (String) body.get("note"));
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private long longNumber(Object value) { return value instanceof Number n ? n.longValue() : 0; }
}
