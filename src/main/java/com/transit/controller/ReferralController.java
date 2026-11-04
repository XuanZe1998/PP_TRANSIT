package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.service.CurrentUserService;
import com.transit.service.ReferralService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ReferralController {
    private final ReferralService referrals;
    private final CurrentUserService users;

    @GetMapping("/public/referrals/invite")
    public Map<String,Boolean> validate(@RequestParam String code) {
        return Map.of("valid",referrals.validInvite(code));
    }
    @GetMapping("/user/referrals")
    public Map<String,Object> summary(@RequestHeader("Authorization") String auth) {
        return referrals.summary(users.requireUser(auth).getId());
    }
    @PostMapping("/user/referrals/enroll")
    public Map<String,Object> enroll(@RequestHeader("Authorization") String auth) {
        return referrals.enroll(users.requireUser(auth).getId());
    }
    @GetMapping("/user/referrals/account")
    public Map<String,Object> account(@RequestHeader("Authorization") String auth) {
        return referrals.account(users.requireUser(auth).getId());
    }
    @PutMapping("/user/referrals/account")
    public void account(@RequestHeader("Authorization") String auth,@RequestBody ReferralService.Account account) {
        referrals.saveAccount(users.requireUser(auth).getId(),account);
    }
    @PostMapping("/user/referrals/withdrawals")
    public Map<String,Object> withdraw(@RequestHeader("Authorization") String auth,@RequestBody ReferralService.Withdrawal request) {
        return referrals.withdraw(users.requireUser(auth).getId(),request);
    }
    @PostMapping("/user/referrals/withdrawals/{id}/cancel")
    public void cancel(@RequestHeader("Authorization") String auth,@PathVariable long id) {
        referrals.cancel(users.requireUser(auth).getId(),id);
    }
    @GetMapping("/user/referrals/lists/{kind}")
    public PageResponse<Map<String,Object>> userList(@RequestHeader("Authorization") String auth,@PathVariable String kind,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) String query,@RequestParam(required=false) String status,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        return referrals.list(kind,users.requireUser(auth).getId(),page,size,query,status,from,to);
    }
    @GetMapping("/admin/api/referrals/lists/{kind}")
    public PageResponse<Map<String,Object>> adminList(@RequestHeader("Authorization") String auth,@PathVariable String kind,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) String query,@RequestParam(required=false) String status,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        users.requireAdmin(auth);
        return referrals.list(kind,null,page,size,query,status,from,to);
    }
    @GetMapping("/admin/api/referrals")
    public Map<String,Object> adminSummary(@RequestHeader("Authorization") String auth) {
        users.requireAdmin(auth);
        return Map.of("overview",referrals.overview(),"settings",referrals.settings());
    }
    @GetMapping("/admin/api/referrals/users/{id}")
    public Map<String,Object> userSummary(@RequestHeader("Authorization") String auth,@PathVariable long id) {
        users.requireAdmin(auth);
        return referrals.summary(id);
    }
    @PutMapping("/admin/api/referrals/settings")
    public Map<String,Object> settings(@RequestHeader("Authorization") String auth,@RequestBody ReferralService.Settings request) {
        return referrals.saveSettings(users.requireAdmin(auth),request);
    }
    @PostMapping("/admin/api/referrals/products/{id}/preview")
    public Map<String,Object> preview(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody ReferralService.RulePreview request) {
        return referrals.preview(users.requireAdmin(auth),id,request);
    }
    @PostMapping("/admin/api/referrals/products/{id}/apply")
    public void apply(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody Map<String,String> request) {
        referrals.applyRule(users.requireAdmin(auth),id,request.get("token"));
    }
    @PutMapping("/admin/api/referrals/members/{id}")
    public void member(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody Map<String,String> request) {
        referrals.memberStatus(users.requireAdmin(auth),id,request.get("status"));
    }
    @PostMapping("/admin/api/referrals/withdrawals/review")
    public void review(@RequestHeader("Authorization") String auth,@RequestBody ReferralService.Review request) {
        referrals.review(users.requireAdmin(auth),request);
    }
    @PostMapping("/admin/api/referrals/withdrawals/{id}/claim")
    public void claim(@RequestHeader("Authorization") String auth,@PathVariable long id) {
        referrals.claim(users.requireAdmin(auth),id);
    }
    @PostMapping("/admin/api/referrals/withdrawals/{id}/reveal")
    public org.springframework.http.ResponseEntity<ReferralService.Account> reveal(@RequestHeader("Authorization") String auth,@PathVariable long id) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(referrals.reveal(users.requireAdmin(auth),id));
    }
    @PostMapping("/admin/api/referrals/withdrawals/{id}/paid")
    public void paid(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody ReferralService.Payment request) {
        referrals.paid(users.requireAdmin(auth),id,request);
    }
    @PostMapping("/admin/api/referrals/withdrawals/{id}/failed")
    public void failed(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody Map<String,String> request) {
        referrals.paymentFailed(users.requireAdmin(auth),id,request.get("note"));
    }
    @PostMapping("/admin/api/referrals/orders/{id}/refunds")
    public void refund(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody ReferralService.Refund request) {
        referrals.refund(users.requireAdmin(auth),id,request);
    }
}
