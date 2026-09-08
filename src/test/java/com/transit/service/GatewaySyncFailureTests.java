package com.transit.service;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class GatewaySyncFailureTests {
 @Test void redirectsAndDecryptionFailuresHaveActionableReasons(){
  var redirect=GatewaySyncJobs.classify(new IllegalStateException("HTTP 301"));
  assertThat(redirect.code()).isEqualTo("UPSTREAM_REDIRECT");assertThat(redirect.message()).contains("301");
  assertThat(GatewaySyncJobs.classify(new IllegalStateException("Unable to decrypt channel credential; verify the configured master key")).code()).isEqualTo("CREDENTIAL_DECRYPTION_FAILED");
 }
 @Test void classifiesHttpErrorsWithoutReturningUpstreamBodiesOrCredentials(){for(int status:new int[]{401,403,404,429,500}){var error=WebClientResponseException.create(status,"raw-secret",HttpHeaders.EMPTY,"Bearer upstream-secret".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);var result=GatewaySyncJobs.classify(error);assertThat(result.httpStatus()).isEqualTo(status);assertThat(result.toString()).doesNotContain("raw-secret","upstream-secret");}}
 @Test void recognizesSafeHttpMessagesTimeoutsAndPartialCatalogs(){assertThat(GatewaySyncJobs.classify(new IllegalStateException("HTTP 401")).code()).isEqualTo("INVALID_CREDENTIAL");assertThat(GatewaySyncJobs.classify(new java.util.concurrent.TimeoutException()).code()).isEqualTo("TIMEOUT");assertThat(GatewaySyncJobs.classify(new IllegalArgumentException("Incomplete catalog")).code()).isEqualTo("INCOMPLETE_CATALOG");}
}
