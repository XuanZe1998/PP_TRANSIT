package com.transit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OAuthProviderIntegrationTests {

    private static final ProviderStub PROVIDER = ProviderStub.start();

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("oauth.github.client-id", () -> "github-test-client");
        registry.add("oauth.github.client-secret", () -> "github-test-secret");
        registry.add("oauth.github.redirect-uri", () -> "http://127.0.0.1:5173/oauth/callback/github");
        registry.add("oauth.github.token-uri", () -> PROVIDER.baseUrl() + "/github/token");
        registry.add("oauth.github.user-uri", () -> PROVIDER.baseUrl() + "/github/user");
        registry.add("oauth.github.emails-uri", () -> PROVIDER.baseUrl() + "/github/emails");
        registry.add("oauth.google.client-id", () -> "google-test-client");
        registry.add("oauth.google.client-secret", () -> "google-test-secret");
        registry.add("oauth.google.redirect-uri", () -> "http://127.0.0.1:5173/oauth/callback/google");
        registry.add("oauth.google.token-uri", () -> PROVIDER.baseUrl() + "/google/token");
        registry.add("oauth.google.user-uri", () -> PROVIDER.baseUrl() + "/google/user");
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @ParameterizedTest(name = "{0} exchanges authorization codes as an HTML form")
    @ValueSource(strings = {"github", "google"})
    void providerTokenExchangeUsesFormEncoding(String provider) {
        PROVIDER.clear();
        String state = authorize(provider);
        Map<String, Object> session = callback(provider, "code for " + provider + " & symbols", state, 200);
        assertThat(session.get("access_token")).isNotNull()
                .isNotEqualTo("provider-token-" + provider);
        assertThat(session).doesNotContainKeys("provider_access_token", "provider_refresh_token");

        CapturedRequest request = PROVIDER.requests().stream()
                .filter(candidate -> candidate.path().equals("/" + provider + "/token"))
                .findFirst().orElseThrow();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).startsWith("application/x-www-form-urlencoded");
        Map<String, String> form = decodeForm(request.body());
        assertThat(form)
                .containsEntry("client_id", provider + "-test-client")
                .containsEntry("client_secret", provider + "-test-secret")
                .containsEntry("code", "code for " + provider + " & symbols")
                .containsEntry("redirect_uri", "http://127.0.0.1:5173/oauth/callback/" + provider);
        if (provider.equals("google")) {
            assertThat(form).containsEntry("grant_type", "authorization_code");
        }

        Map<String, Object> binding = jdbcTemplate.queryForMap("""
                SELECT access_token, refresh_token
                FROM oauth_user_bindings WHERE provider = ?
                ORDER BY id DESC LIMIT 1
                """, provider);
        assertThat(binding.get("access_token")).isNull();
        assertThat(binding.get("refresh_token")).isNull();
    }

    @Test
    void missingWrongAndCrossProviderStatesFailBeforeTokenExchange() {
        PROVIDER.clear();
        client.get().uri(uri -> uri.path("/oauth/callback/github")
                        .queryParam("code", "missing-state").build())
                .exchange().expectStatus().isBadRequest()
                .expectBody().consumeWith(ignored -> { });
        assertThat(PROVIDER.tokenRequestCount()).isZero();

        String githubState = authorize("github");
        callback("github", "wrong-state-code", "not-the-issued-state", 400);
        callback("google", "cross-provider-code", githubState, 400);
        assertThat(PROVIDER.tokenRequestCount()).isZero();

        callback("github", "correct-state-code", githubState, 200);
        assertThat(PROVIDER.tokenRequestCount()).isEqualTo(1);
    }

    @Test
    void oauthStateIsHashedAtRestAndCannotBeReplayed() {
        PROVIDER.clear();
        String state = authorize("google");
        String stored = jdbcTemplate.queryForObject("""
                SELECT state_hash FROM oauth_login_states
                WHERE provider = 'google' ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(stored).startsWith("sha256:").isNotEqualTo(state);

        callback("google", "first-use", state, 200);
        callback("google", "replay-must-fail", state, 400);
        assertThat(PROVIDER.tokenRequestCount()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT consumed_at FROM oauth_login_states WHERE state_hash = ?", stored);
        assertThat(row.get("consumed_at")).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private String authorize(String provider) {
        Map<String, String> response = client.get().uri(uri -> uri.path("/oauth/authorize")
                        .queryParam("provider", provider).build())
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        String state = response.get("state");
        assertThat(state).isNotBlank();
        assertThat(response.get("url")).contains("state=").contains(provider.equals("google")
                ? "google-test-client" : "github-test-client");
        return state;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callback(String provider, String code, String state, int expectedStatus) {
        WebTestClient.ResponseSpec response = client.get().uri(uri -> uri
                        .path("/oauth/callback/{provider}")
                        .queryParam("code", code)
                        .queryParam("state", state)
                        .build(provider))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
        if (expectedStatus != 200) {
            response.expectBody().consumeWith(ignored -> { });
            return Map.of();
        }
        Map<String, Object> body = response.expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private static Map<String, String> decodeForm(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .forEach(pair -> values.put(
                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        return values;
    }

    private record CapturedRequest(String method, String path, String contentType, String body) {
    }

    private static final class ProviderStub {
        private final HttpServer server;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private ProviderStub(HttpServer server) {
            this.server = server;
        }

        static ProviderStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ProviderStub stub = new ProviderStub(server);
                server.createContext("/", stub::handle);
                server.start();
                return stub;
            } catch (IOException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<CapturedRequest> requests() {
            return requests;
        }

        long tokenRequestCount() {
            return requests.stream().filter(request -> request.path().endsWith("/token")).count();
        }

        void clear() {
            requests.clear();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestMethod(), path,
                    String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")), body));

            String json = switch (path) {
                case "/github/token" -> "{\"access_token\":\"provider-token-github\",\"token_type\":\"bearer\"}";
                case "/github/user" -> "{\"id\":\"github-provider-user\",\"login\":\"github_test_user\",\"email\":\"github-provider@example.com\"}";
                case "/github/emails" -> "[{\"email\":\"github-provider@example.com\",\"verified\":true,\"primary\":true}]";
                case "/google/token" -> "{\"access_token\":\"provider-token-google\",\"token_type\":\"Bearer\"}";
                case "/google/user" -> "{\"sub\":\"google-provider-user\",\"name\":\"Google Test User\",\"email\":\"google-provider@example.com\",\"verified_email\":true}";
                default -> "{\"error\":\"not_found\"}";
            };
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.equals("/") ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
