package com.transit.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.User;
import com.transit.service.AutoMovieService;
import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/creative")
@RequiredArgsConstructor
public class AutoMovieController {
    private final AutoMovieService service;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    @GetMapping("/auto-movie/catalog") public Map<String, Object> catalog() { return service.catalog(); }
    @PostMapping("/projects") public Map<String, Object> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestBody Map<String, Object> body) { return service.create(user(auth), body); }
    @PostMapping(value = "/projects/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importText(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @RequestPart("file") MultipartFile file,
                                           @RequestPart(value = "options", required = false) String options) throws Exception {
        Map<String, Object> body = options == null ? Map.of() : objectMapper.readValue(options, new TypeReference<>() {});
        return service.importText(user(auth), file, body);
    }
    @GetMapping("/projects") public List<Map<String, Object>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) { return service.list(user(auth)); }
    @GetMapping("/projects/{id}") public Map<String, Object> detail(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) { return service.detail(user(auth), id); }
    @DeleteMapping("/projects/{id}") public Map<String, Object> delete(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) { service.delete(user(auth), id); return Map.of("deleted", true); }
    @PostMapping("/projects/{id}/script/generate") public Map<String, Object> scriptGenerate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.enqueueScript(user(auth), id, body); }
    @PutMapping("/projects/{id}/script") public Map<String, Object> scriptUpdate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.updateScript(user(auth), id, body); }
    @PostMapping("/projects/{id}/script/approve") public Map<String, Object> scriptApprove(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.approveScript(user(auth), id, body); }
    @PostMapping("/projects/{id}/visuals/generate") public Map<String, Object> visualsGenerate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.enqueueVisuals(user(auth), id, body); }
    @PostMapping("/projects/{id}/visuals/approve") public Map<String, Object> visualsApprove(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.approveVisuals(user(auth), id, body); }
    @PutMapping("/projects/{id}/assets/{assetId}") public Map<String, Object> assetUpdate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @PathVariable Long assetId, @RequestBody Map<String, Object> body) { return service.updateAsset(user(auth), id, assetId, body); }
    @PostMapping("/projects/{id}/assets/{assetId}/regenerate") public Map<String, Object> assetRegenerate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @PathVariable Long assetId, @RequestBody Map<String, Object> body) { return service.regenerateAsset(user(auth), id, assetId, body); }
    @PostMapping(value = "/projects/{id}/assets/{assetId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> assetUpload(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @PathVariable Long assetId, @RequestPart("file") MultipartFile file, @RequestParam int version) { return service.uploadAsset(user(auth), id, assetId, file, version); }
    @PutMapping("/projects/{id}/shots/{shotId}") public Map<String, Object> shotUpdate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @PathVariable Long shotId, @RequestBody Map<String, Object> body) { return service.updateShot(user(auth), id, shotId, body); }
    @PostMapping("/projects/{id}/videos/generate") public Map<String, Object> videosGenerate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.enqueueVideos(user(auth), id, body); }
    @PostMapping("/projects/{id}/shots/{shotId}/retry") public Map<String, Object> shotRetry(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @PathVariable Long shotId, @RequestBody Map<String, Object> body) { return service.retryShot(user(auth), id, shotId, body); }
    @PostMapping("/projects/{id}/compose") public Map<String, Object> compose(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.enqueueCompose(user(auth), id, body); }
    @PostMapping("/projects/{id}/cancel") public Map<String, Object> cancel(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.cancel(user(auth), id, body); }
    @PostMapping("/projects/{id}/quote") public Map<String, Object> quote(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id, @RequestBody Map<String, Object> body) { return service.quote(user(auth), id, String.valueOf(body.get("stage"))); }
    private User user(String auth) { return currentUserService.requireUser(auth); }
}
