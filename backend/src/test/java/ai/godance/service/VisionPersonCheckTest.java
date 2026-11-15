package ai.godance.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.InputsProperties;
import ai.godance.config.OpenAIProperties;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.ChatDtos.ChatChoice;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ChatDtos.Usage;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.ModelMetadata;
import ai.godance.gateway.ProviderGateway;
import ai.godance.gateway.providers.OpenAIProvider;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.service.InputPersonCheck.Verdict;
import ai.godance.storage.ImageMetadataTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link VisionPersonCheck}: the question it asks, the only two answers it
 * takes, everything else failing closed, what it records, and the start-up
 * check of the configured model. The provider is a real
 * {@link OpenAIProvider} against a WireMock cassette.
 */
class VisionPersonCheckTest {

    private static final String CHECK_MODEL = "gpt-4o-mini";
    private static final UUID USER = UUID.randomUUID();
    private static final byte[] IMAGE = ImageMetadataTest.fixture("plain.png");

    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private final ModelCatalog catalog = mock(ModelCatalog.class);
    private final UsageRecordRepository usage = mock(UsageRecordRepository.class);
    private WireMockServer openAi;

    @BeforeEach
    void start() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
        ModelInfo model = ModelInfo.text(CHECK_MODEL, Provider.OPENAI, Modality.TEXT, 150L, 600L, 4096, null);
        when(catalog.find(CHECK_MODEL)).thenReturn(Optional.of(model));
        when(catalog.metadata(CHECK_MODEL)).thenReturn(ModelMetadata.of("GPT-4o mini", null,
                Map.of("inputs", Map.of("images", Map.of("max", 10)))));
        when(catalog.find("gpt-text-only")).thenReturn(Optional.of(
                ModelInfo.text("gpt-text-only", Provider.OPENAI, Modality.TEXT, 150L, 600L, 4096, null)));
        when(catalog.metadata("gpt-text-only")).thenReturn(ModelMetadata.NONE);
        when(catalog.find("gpt-image-2")).thenReturn(Optional.of(
                ModelInfo.imageAsync("gpt-image-2", Provider.OPENAI, 53_000L, null)));
        when(catalog.metadata("gpt-image-2")).thenReturn(ModelMetadata.of(null, null,
                Map.of("inputs", Map.of("images", Map.of("max", 16)))));
        when(catalog.find("no-such-model")).thenReturn(Optional.empty());
    }

    @AfterEach
    void stop() {
        openAi.stop();
    }

    // ===== The call ====================================================

    @Test
    void check_givenAnImage_expectsOneFixedQuestionWithTheImageAndNothingElse() {
        answer("no");

        check(CHECK_MODEL, "local", Duration.ofSeconds(5)).check(USER, IMAGE, "image/png");

        assertThat(openAi.getAllServeEvents()).hasSize(1);
        JsonNode body = json.readTree(openAi.getAllServeEvents().get(0).getRequest().getBodyAsString());
        assertThat(body.path("model").asString()).isEqualTo(CHECK_MODEL);
        assertThat(body.path("temperature").asDouble()).isZero();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(VisionPersonCheck.MAX_TOKENS).isLessThanOrEqualTo(8);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
        JsonNode parts = body.path("messages").get(0).path("content");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).path("text").asString()).isEqualTo(VisionPersonCheck.INSTRUCTION)
                .contains("person").contains("yes or no");
        assertThat(parts.get(1).path("image_url").path("url").asString())
                .isEqualTo("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(IMAGE));
    }

    @ParameterizedTest
    @CsvSource({"yes,PERSON", "Yes,PERSON", "YES.,PERSON", "' yes\n',PERSON", "no,NO_PERSON", "No.,NO_PERSON", "NO,NO_PERSON"})
    void check_givenAClearAnswer_expectsThatVerdict(String answer, Verdict expected) {
        answer(answer);

        assertThat(check(CHECK_MODEL, "local", Duration.ofSeconds(5)).check(USER, IMAGE, "image/png"))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "maybe", "unsure", "yes and no", "no, but there is a hand", "Yes, a person is visible.",
            "I cannot help with that.", "nope", "yess", "no no", "y", "n", "0", "false", "是", "No!!", "\"no\""})
    void check_givenAnythingButAClearAnswer_expectsUnavailable(String answer) {
        answer(answer);

        assertThat(check(CHECK_MODEL, "local", Duration.ofSeconds(5)).check(USER, IMAGE, "image/png"))
                .isEqualTo(Verdict.UNAVAILABLE);
    }

    @Test
    void check_givenAModelThatTakesLongerThanTheLimit_expectsUnavailable() {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json").withBody(body("no")).withFixedDelay(2_000)));

        long started = System.nanoTime();
        Verdict verdict = check(CHECK_MODEL, "local", Duration.ofMillis(300)).check(USER, IMAGE, "image/png");

        assertThat(verdict).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_800));
        // The production limit is the brief's fifteen seconds.
        assertThat(VisionPersonCheck.TIMEOUT).isEqualTo(Duration.ofSeconds(15));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429, 500, 503})
    void check_givenAProviderError_expectsUnavailable(int status) {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(status)
                .withHeader("content-type", "application/json").withBody("{\"error\":{\"message\":\"no\"}}")));

        assertThat(check(CHECK_MODEL, "local", Duration.ofSeconds(5)).check(USER, IMAGE, "image/png"))
                .isEqualTo(Verdict.UNAVAILABLE);

        ArgumentCaptor<UsageRecord> saved = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usage).save(saved.capture());
        assertThat(saved.getValue().getOutcome()).isEqualTo(status == 429 ? CallOutcome.RATE_LIMITED : CallOutcome.ERROR);
        assertThat(saved.getValue().getCostCents()).isZero();
    }

    @Test
    void check_givenNoChoiceOrSeveral_expectsUnavailable() {
        assertThat(VisionPersonCheck.read(new ChatResponse("c", CHECK_MODEL, List.of(), new Usage(1, 0, 1), 1)))
                .isEqualTo(Verdict.UNAVAILABLE);
        assertThat(VisionPersonCheck.read(new ChatResponse("c", CHECK_MODEL, null, new Usage(1, 0, 1), 1)))
                .isEqualTo(Verdict.UNAVAILABLE);
        ChatChoice no = new ChatChoice(0, new ChatMessage("assistant", "no"), "stop");
        ChatChoice yes = new ChatChoice(1, new ChatMessage("assistant", "yes"), "stop");
        assertThat(VisionPersonCheck.read(new ChatResponse("c", CHECK_MODEL, List.of(no, yes), new Usage(1, 0, 1), 1)))
                .isEqualTo(Verdict.UNAVAILABLE);
        assertThat(VisionPersonCheck.read(new ChatResponse("c", CHECK_MODEL,
                List.of(new ChatChoice(0, new ChatMessage("assistant", (String) null), "content_filter")),
                new Usage(1, 0, 1), 1))).isEqualTo(Verdict.UNAVAILABLE);
    }

    // ===== What it records =============================================

    @Test
    void check_givenAnAnswer_expectsAUsageRecordThatChargesNobodyAndSaysWhatItCostThePlatform() {
        answer("no", 1_300, 1);

        check(CHECK_MODEL, "local", Duration.ofSeconds(5)).check(USER, IMAGE, "image/png");

        ArgumentCaptor<UsageRecord> saved = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usage).save(saved.capture());
        UsageRecord record = saved.getValue();
        assertThat(record.getUserId()).isEqualTo(USER);
        assertThat(record.getModelName()).isEqualTo(CHECK_MODEL);
        assertThat(record.getRequestId()).startsWith("check_");
        assertThat(record.getOutcome()).isEqualTo(CallOutcome.OK);
        assertThat(record.getInputTokens()).isEqualTo(1_300);
        assertThat(record.getOutputTokens()).isEqualTo(1);
        // Nobody is charged...
        assertThat(record.getCostCents()).isZero();
        assertThat(record.getCostMicrocents()).isZero();
        // ...and the provider's price for it is on record: 1,300 × 150 / 1000 + 1 × 600 / 1000 = 195 microcents.
        assertThat(record.getProviderCostMicrocents()).isEqualTo(195L);
    }

    @Test
    void check_givenUsageThatCannotBeSaved_expectsTheVerdictAllTheSame() {
        answer("yes");
        when(usage.save(any())).thenThrow(new IllegalStateException("database is down"));

        assertThat(check(CHECK_MODEL, "local", Duration.ofSeconds(5)).check(USER, IMAGE, "image/png"))
                .isEqualTo(Verdict.PERSON);
    }

    // ===== No model, a wrong model, a region that may not use it =======

    @Test
    void check_givenNoCheckModel_expectsUnavailableAndNoCall() {
        VisionPersonCheck none = check("", "local", Duration.ofSeconds(5));

        assertThatCode(none::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(none.check(USER, IMAGE, "image/png")).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(openAi.getAllServeEvents()).isEmpty();
        verifyNoInteractions(usage);
    }

    @Test
    void startUp_givenACheckModelThisRegionMayUse_expectsItAccepted() {
        assertThatCode(check(CHECK_MODEL, "digitalocean", Duration.ofSeconds(5))::afterSingletonsInstantiated)
                .doesNotThrowAnyException();
    }

    @Test
    void startUp_givenACheckModelTheRegionMayNotUse_expectsAConfigurationErrorAndNoCallEver() {
        // OpenAI from the Alicloud region: the allowlist refuses it, for the check like any other call.
        VisionPersonCheck inAlicloud = check(CHECK_MODEL, "alicloud", Duration.ofSeconds(5));

        assertThatThrownBy(inAlicloud::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("godance.inputs.check-model")
                .hasMessageContaining("OPENAI")
                .hasMessageContaining("may not use");
        // And were it ever asked, it would not call out.
        assertThat(inAlicloud.check(USER, IMAGE, "image/png")).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-such-model", "gpt-text-only", "gpt-image-2"})
    void startUp_givenACheckModelThatIsNotAChatModelThatAcceptsImages_expectsAConfigurationError(String model) {
        VisionPersonCheck wrong = check(model, "local", Duration.ofSeconds(5));

        assertThatThrownBy(wrong::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("godance.inputs.check-model");
        assertThat(wrong.check(USER, IMAGE, "image/png")).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    // ===== Helpers =====================================================

    private VisionPersonCheck check(String model, String region, Duration timeout) {
        InputsProperties props = new InputsProperties(true, false, model, 1, 24, DataSize.ofMegabytes(20), 40, 200,
                Duration.ofMinutes(5), Duration.ofHours(1), 60, 120);
        OpenAIProvider provider = new OpenAIProvider(
                new OpenAIProperties("test-key", openAi.baseUrl(), Duration.ofSeconds(10)), json);
        return new VisionPersonCheck(props, catalog, new ProviderGateway(List.of(provider), region), usage, timeout);
    }

    private void answer(String content) {
        answer(content, 1_200, 1);
    }

    private void answer(String content, int promptTokens, int completionTokens) {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody(body(content, promptTokens, completionTokens))));
    }

    private String body(String content) {
        return body(content, 1_200, 1);
    }

    private String body(String content, int promptTokens, int completionTokens) {
        return json.writeValueAsString(Map.of(
                "id", "chatcmpl-check", "model", CHECK_MODEL, "created", 1_700_000_000,
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens,
                        "total_tokens", promptTokens + completionTokens)));
    }
}
