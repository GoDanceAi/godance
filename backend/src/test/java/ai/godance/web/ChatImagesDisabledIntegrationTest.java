package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.InputPersonCheck;
import ai.godance.service.InputRefusedException;
import ai.godance.storage.ImageMetadataTest;

/**
 * Chat as it ships: inputs switched off, nothing configured. An image part
 * answers 403 {@code inputs_disabled} before anything is fetched or
 * charged, and a request of plain strings is exactly what it was.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ChatImagesDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAi;

    @BeforeAll
    static void startMock() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
    }

    @AfterAll
    static void stopMock() {
        if (openAi != null) openAi.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired InputPersonCheck personCheck;

    @BeforeEach
    void reset() {
        openAi.resetAll();
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hi.\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}")));
    }

    @Test
    void chat_givenAnImagePartAndInputsDisabled_expects403InputsDisabledAndNothingCharged() {
        Signed u = signup();
        long rowsBefore = ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 50)).getTotalElements();
        String dataUrl = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(ImageMetadataTest.fixture("plain.png"));

        // Whichever way the image is named, and whether or not the model could have taken it.
        for (String model : List.of("gpt-4o", "deepseek-v4-pro")) {
            for (String url : List.of(dataUrl, "https://example.com/cat.png",
                    "https://api.godance.test/v1/files/" + UUID.randomUUID() + "/content")) {
                http.post().uri("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, u.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", "What is this?"),
                                Map.of("type", "image_url", "image_url", Map.of("url", url)))))))
                        .exchange()
                        .expectStatus().isForbidden()
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_DISABLED);
            }
        }

        assertThat(openAi.getAllServeEvents()).isEmpty();
        assertThat(ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(rowsBefore);
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10_00);
    }

    @Test
    void chat_givenStringContent_expectsExactlyTheRequestItAlwaysSent() {
        Signed u = signup();

        http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "max_tokens", 32, "temperature", 0.5, "messages", List.of(
                        Map.of("role", "system", "content", "Be brief."),
                        Map.of("role", "user", "content", "Hello there"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.role").isEqualTo("assistant")
                .jsonPath("$.choices[0].message.content").isEqualTo("Hi.");

        assertThat(openAi.getAllServeEvents()).hasSize(1);
        assertThat(openAi.getAllServeEvents().get(0).getRequest().getBodyAsString()).isEqualTo(
                "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"system\",\"content\":\"Be brief.\"},"
                        + "{\"role\":\"user\",\"content\":\"Hello there\"}],\"max_tokens\":32,\"temperature\":0.5,"
                        + "\"stream\":false}");
    }

    @Test
    void chat_givenContentAsTextPartsAndInputsDisabled_expectsItAnsweredAsText() {
        // Text in OpenAI's parts form is not an input: it works with inputs off.
        Signed u = signup();

        http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "messages", List.of(Map.of("role", "user", "content",
                        List.of(Map.of("type", "text", "text", "Hello"), Map.of("type", "text", "text", "there"))))))
                .exchange()
                .expectStatus().isOk();

        assertThat(openAi.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .contains("{\"role\":\"user\",\"content\":\"Hello\\nthere\"}");
    }

    @Test
    void chat_givenBlankOrMissingContent_expects400AsBefore() {
        Signed u = signup();

        for (Map<String, Object> message : List.of(
                Map.<String, Object>of("role", "user", "content", ""),
                Map.<String, Object>of("role", "user", "content", "   "),
                Map.<String, Object>of("role", "user"),
                Map.<String, Object>of("content", "Hello"))) {
            http.post().uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, u.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("model", "gpt-4o", "messages", List.of(message)))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    @Test
    void personCheck_givenNoCheckModel_expectsUnavailable() {
        // As it ships there is no check model, so the check cannot clear anything.
        assertThat(personCheck.check(UUID.randomUUID(), ImageMetadataTest.fixture("plain.png"), "image/png"))
                .isEqualTo(InputPersonCheck.Verdict.UNAVAILABLE);
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signup() {
        String email = "chat-off-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 10_00, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, bearer);
    }
}
