package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.provider.HaoeeProtocolClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelTaskService {
    private final UniversalModelService models;
    private final HaoeeProtocolClient haoee;
    private final GatewaySettlementService settlement;
    private final IdempotencyService idempotency;
    private final ProviderCredentialService credentials;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Mono<JsonNode> create(String authorization, String clientIp, JsonNode request, String key) {
        UniversalModelService.AuthContext auth = models.authorize(authorization, clientIp, request);
        IdempotencyService.Claim claim = idempotency.claim("API_KEY", auth.token().getId(),
                "model.task.create", key, request, true);
        if (claim.replay()) return Mono.just(claim.response());
        UniversalModelService.Route route = models.route(auth.model(), "tasks");
        String endpoint = route.mapping().getEndpointPath();
        if (endpoint == null || endpoint.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Task route has no create endpoint");
        }
        if (!request.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON object body is required");
        ObjectNode payload = (ObjectNode) request.deepCopy();
        payload.put("model", route.mapping().getChannelModelName());
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "");
        String reservationId = "res_" + taskId.substring(5);
        int estimatedTokens = Math.max(1, payload.toString().getBytes(StandardCharsets.UTF_8).length / 3);
        UniversalModelService.PricingQuote pricing = models.estimateQuote(route.mapping(), payload);
        long reservedAmount = pricing.saleAmount();
        GatewaySettlementService.Reservation reservation = settlement.reserve(auth.token(), auth.user(),
                estimatedTokens, reservedAmount, reservationId, auth.model());
        insertTask(taskId, auth, route, payload, reservationId, reservedAmount, pricing);
        return haoee.invoke(route.channel(), route.mapping().getChannelModelName(), endpoint,
                        HttpMethod.POST, payload)
                .publishOn(Schedulers.boundedElastic())
                .map(response -> {
                    String upstreamId = upstreamTaskId(response);
                    String status = normalizeStatus(response, "SUBMITTED");
                    updateTask(taskId, upstreamId, status, response, null);
                    if (success(status)) settlement.settle(reservation, estimatedTokens, reservedAmount,
                            "Async model task " + taskId);
                    if (failed(status)) settlement.release(reservation, "Upstream task failed during creation");
                    JsonNode result = taskView(taskId);
                    idempotency.complete(claim, 201, result, "MODEL_TASK", taskId);
                    credentials.recordSuccess(route.credentialId(), 0);
                    return result;
                })
                .onErrorResume(error -> {
                    boolean unknown = models.ambiguousTimeout(error);
                    if (unknown) settlement.markUnknown(reservation, safe(error));
                    else settlement.release(reservation, safe(error));
                    updateTask(taskId, null, unknown ? "UNKNOWN" : "FAILED", null, safe(error));
                    if (unknown) idempotency.unknown(claim, error, "MODEL_TASK", taskId);
                    else idempotency.fail(claim, error);
                    if (unknown) credentials.releaseUnknown(route.credentialId());
                    else credentials.recordFailure(route.credentialId(), error);
                    return Mono.error(error);
                });
    }

    public Mono<JsonNode> get(String authorization, String clientIp, String taskId) {
        Map<String, Object> row = taskRow(taskId);
        ObjectNode authBody = objectMapper.createObjectNode().put("model", String.valueOf(row.get("model")));
        UniversalModelService.AuthContext auth = models.authorize(authorization, clientIp, authBody);
        if (((Number) row.get("token_id")).longValue() != auth.token().getId()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        String current = String.valueOf(row.get("status"));
        if (success(current) || failed(current)) return Mono.just(taskView(taskId));
        String upstreamId = Objects.toString(row.get("upstream_task_id"), "");
        if (upstreamId.isBlank()) return Mono.just(taskView(taskId));
        UniversalModelService.Route route = models.route(String.valueOf(row.get("model")), "tasks");
        String queryPath = route.mapping().getTaskQueryPath();
        if (queryPath == null || queryPath.isBlank()) return Mono.just(taskView(taskId));
        return haoee.query(route.channel(), route.mapping().getChannelModelName(), queryPath,
                        route.mapping().getTaskQueryMethod(), upstreamId)
                .publishOn(Schedulers.boundedElastic())
                .map(response -> {
                    String status = normalizeStatus(response, current);
                    updateTask(taskId, upstreamId, status, response, null);
                    finishReservation(row, taskId, status);
                    credentials.recordSuccess(route.credentialId(), 0);
                    return taskView(taskId);
                })
                .onErrorResume(error -> {
                    jdbcTemplate.update("UPDATE model_tasks SET error_message=?,updated_at=? WHERE task_id=?",
                            safe(error), LocalDateTime.now(), taskId);
                    if (models.ambiguousTimeout(error)) credentials.releaseUnknown(route.credentialId());
                    else credentials.recordFailure(route.credentialId(), error);
                    return Mono.error(error);
                });
    }

    @Transactional
    protected void insertTask(String taskId, UniversalModelService.AuthContext auth,
                              UniversalModelService.Route route, JsonNode request,
                              String reservationId, long reservedAmount,
                              UniversalModelService.PricingQuote pricing) {
        jdbcTemplate.update("""
                INSERT INTO model_tasks
                (task_id,user_id,token_id,organization_id,model,capability,channel_id,credential_id,
                 status,request_json,reserved_amount,reservation_id,billing_unit,billable_quantity,
                 unit_sale_price,unit_cost_price,next_poll_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?, 'CREATING',?,?,?,?,?,?,?,?,?,?)
                """, taskId, auth.user().getId(), auth.token().getId(), auth.token().getOrganizationId(),
                auth.model(), route.mapping().getCapability(), route.channel().getId(), route.credentialId(),
                json(request), reservedAmount, reservationId, pricing.unit(), pricing.quantity(),
                pricing.saleUnitPrice(), pricing.costUnitPrice(), LocalDateTime.now().plusSeconds(3),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void finishReservation(Map<String, Object> row, String taskId, String status) {
        String reservationId = Objects.toString(row.get("reservation_id"), "");
        if (reservationId.isBlank()) return;
        GatewaySettlementService.Reservation reservation = settlement.restore(reservationId);
        if (success(status)) settlement.settle(reservation, reservation.reservedTokens(),
                reservation.reservedAmount(), "Async model task " + taskId);
        else if (failed(status)) settlement.release(reservation, "Async model task ended with " + status);
    }

    private void updateTask(String taskId, String upstreamId, String status, JsonNode response, String error) {
        boolean terminal = success(status) || failed(status);
        jdbcTemplate.update("""
                UPDATE model_tasks SET upstream_task_id=COALESCE(?,upstream_task_id),status=?,response_json=?,
                       error_message=?,next_poll_at=?,updated_at=?,completed_at=? WHERE task_id=?
                """, upstreamId, status, response == null ? null : json(response), error,
                terminal ? null : LocalDateTime.now().plusSeconds(5), LocalDateTime.now(),
                terminal ? LocalDateTime.now() : null, taskId);
    }

    private Map<String, Object> taskRow(String taskId) {
        return jdbcTemplate.queryForList("SELECT * FROM model_tasks WHERE task_id=?", taskId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }

    private JsonNode taskView(String taskId) {
        Map<String, Object> row = taskRow(taskId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", taskId);
        result.put("object", "model.task");
        result.put("model", String.valueOf(row.get("model")));
        result.put("capability", String.valueOf(row.get("capability")));
        result.put("status", String.valueOf(row.get("status")));
        if (row.get("upstream_task_id") != null) result.put("upstream_task_id", String.valueOf(row.get("upstream_task_id")));
        if (row.get("response_json") != null) result.set("result", parse(String.valueOf(row.get("response_json"))));
        if (row.get("error_message") != null) result.put("error", String.valueOf(row.get("error_message")));
        result.put("created_at", String.valueOf(row.get("created_at")));
        return result;
    }

    private String upstreamTaskId(JsonNode response) {
        for (String field : new String[]{"task_id", "taskId", "id"}) {
            if (response.path(field).isValueNode()) return response.path(field).asText();
            if (response.path("data").path(field).isValueNode()) return response.path("data").path(field).asText();
        }
        return null;
    }

    private String normalizeStatus(JsonNode response, String fallback) {
        String raw = response.path("status").asText(response.path("state").asText(
                response.path("data").path("status").asText(fallback)));
        String value = raw.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "DONE", "FINISHED" -> "SUCCEEDED";
            case "FAIL", "FAILED", "ERROR", "CANCELED", "CANCELLED" -> "FAILED";
            case "QUEUED", "PENDING", "SUBMITTED" -> "SUBMITTED";
            default -> "RUNNING";
        };
    }

    private boolean success(String status) { return "SUCCEEDED".equals(status); }
    private boolean failed(String status) { return "FAILED".equals(status); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    private String safe(Throwable error) {
        String value = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        return value.substring(0, Math.min(1000, value.length()));
    }
}
