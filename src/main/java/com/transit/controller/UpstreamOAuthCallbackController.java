package com.transit.controller;

import com.transit.service.ProviderAccountAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Dedicated upstream-account callback boundary, separate from end-user login OAuth. */
@RestController
@RequiredArgsConstructor
public class UpstreamOAuthCallbackController {
    private final ProviderAccountAdminService accounts;

    @GetMapping("/upstream/oauth/callback/{platform}")
    public ResponseEntity<String> callback(@PathVariable String platform,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(required = false) String error) {
        Map<String, Object> status = accounts.status();
        if (!Boolean.TRUE.equals(status.get("oauthEnabled"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上游 OAuth 号池未启用");
        }
        if (error != null && !error.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游授权未完成");
        if (code == null || code.isBlank() || state == null || state.length() < 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游 OAuth 回调参数无效");
        }
        Map<String, Object> result = accounts.oauthCallback(platform, code, state);
        String payload = result.entrySet().stream().map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(String.valueOf(entry.getValue())) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        String html = "<!doctype html><meta charset=utf-8><meta name=oauth-result content=\"" + html(payload) + "\">" +
                "<title>OAuth authorization completed</title><p>授权成功，此窗口可以关闭。</p>" +
                "<script src=/upstream/oauth/callback/result.js></script>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c").replace(">", "\\u003e"); }
    private String html(String value) { return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }

    @GetMapping(value="/upstream/oauth/callback/result.js", produces="application/javascript")
    public String resultScript() {
        return "(()=>{const e=document.querySelector('meta[name=oauth-result]');let r={};try{r=JSON.parse(e?.content||'{}')}catch(_){}if(window.opener){window.opener.postMessage({type:'linknux-upstream-oauth',result:r},location.origin)}window.close()})();";
    }
}
