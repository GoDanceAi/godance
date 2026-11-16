package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * What the bring-your-own-key tests (round 14 brief 03) share: accounts, a
 * stored key, and the provider answers WireMock gives. Every key here is
 * visibly fake; the platform's keys are set by each test class.
 */
final class ByokFixture {

    /** The platform's own keys in these tests: visibly fake, and never the caller's. */
    static final String PLATFORM_OPENAI_KEY = "sk-platform-FAKE-openai-0000000000000000";
    static final String PLATFORM_ANTHROPIC_KEY = "sk-ant-platform-FAKE-0000000000000000";

    /** A 1×1 transparent PNG: what the image upstream returns. */
    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    record Account(UUID id, String email, String bearer) { }

    private final WebTestClient http;
    private final UserRepository users;
    private final CreditLedgerService ledger;

    ByokFixture(WebTestClient http, UserRepository users, CreditLedgerService ledger) {
        this.http = http;
        this.users = users;
        this.ledger = ledger;
    }

    /** A fresh caller's own key: visibly fake, and different every time. */
    static String userKey(String prefix) {
        return prefix + "-FAKE-user-" + UUID.randomUUID().toString().replace("-", "");
    }

    Account account(long cents) {
        String email = "byok-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Byok Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) ledger.topUp(id, cents, CreditSource.PROMO, "seed-" + id, "seed-" + id, "test seed");
        return new Account(id, email, "Bearer " + resp.accessToken());
    }

    /** An admin with 2FA on, signed in before 2FA was turned on. */
    Account admin(UserTwoFactorRepository twoFactors) {
        Account a = account(0);
        User u = users.findById(a.id()).orElseThrow();
        u.setRole(UserRole.ADMIN);
        u.setEmailVerified(true);
        users.save(u);
        Account signedIn = new Account(a.id(), a.email(), logIn(a.email()));
        TwoFactorFixture.turnOn(twoFactors, a.id());
        return signedIn;
    }

    private String logIn(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    String apiKey(Account a) {
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", a.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "byok-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        return "Bearer " + key.plaintext();
    }

    // ===== Credentials ==================================================

    WebTestClient.ResponseSpec putCredential(String bearer, String provider, String key, Boolean fallback) {
        Map<String, Object> body = new HashMap<>();
        if (key != null) body.put("api_key", key);
        if (fallback != null) body.put("fallback_to_platform", fallback);
        return http.put().uri("/v1/provider-credentials/{p}", provider)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** Stores an OpenAI key the (stubbed) provider accepts. */
    void saveOpenAiKey(WireMockServer openAi, Account a, String key, boolean fallback) {
        openAi.stubFor(get(urlEqualTo("/v1/models")).withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"object\":\"list\",\"data\":[]}")));
        putCredential(a.bearer(), "OPENAI", key, fallback).expectStatus().isOk();
    }

    /** Stores an Anthropic key the (stubbed) provider accepts. */
    void saveAnthropicKey(WireMockServer anthropic, Account a, String key, boolean fallback) {
        anthropic.stubFor(get(urlPathEqualTo("/v1/models")).withHeader("x-api-key", equalTo(key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"data\":[],\"has_more\":false}")));
        putCredential(a.bearer(), "ANTHROPIC", key, fallback).expectStatus().isOk();
    }

    List<Map<String, Object>> credentials(String bearer) {
        return http.get().uri("/v1/provider-credentials")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() { })
                .returnResult().getResponseBody();
    }

    // ===== Provider answers =============================================

    /** OpenAI's chat answer to a request made with {@code key}: 1,000 prompt and 500 completion tokens. */
    static void stubOpenAiChat(WireMockServer openAi, String key) {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"chatcmpl-byok\",\"object\":\"chat.completion\",\"created\":1700000000,"
                                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"Hello.\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500,\"total_tokens\":1500}}")));
    }

    /** OpenAI refusing {@code key}, as it does a revoked one. */
    static void stubOpenAiRefuses(WireMockServer openAi, String path, String key) {
        openAi.stubFor(post(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(401).withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"message\":\"Incorrect API key provided.\",\"type\":\"invalid_request_error\","
                                + "\"code\":\"invalid_api_key\"}}")));
    }

    static void stubOpenAiImage(WireMockServer openAi, String key) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        openAi.stubFor(post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    // ===== Requests =====================================================

    static Map<String, Object> chatBody(String model, String content) {
        return Map.of("model", model, "max_tokens", 600,
                "messages", List.of(Map.of("role", "user", "content", content)));
    }

    WebTestClient.ResponseSpec chat(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/chat/completions")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    UUID submitImage(String bearer, String model) {
        return http.post().uri("/v1/images/generations")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", model, "prompt", "A brass teapot on linen", "n", 1))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
    }

    JobView awaitJob(UUID jobId, String bearer, JobStatus wanted) {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header("Authorization", bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo(wanted);
        return view;
    }

    Map<String, Object> estimate(String bearer, String model) {
        return http.post().uri("/v1/estimates")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", model, "n", 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
    }

    /** A problem's {@code code}, read off a refused response. */
    static String codeOf(WebTestClient.ResponseSpec response, int status) {
        Map<String, Object> problem = response.expectStatus().isEqualTo(status)
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
        assertThat(problem).isNotNull();
        return (String) problem.get("code");
    }
}
