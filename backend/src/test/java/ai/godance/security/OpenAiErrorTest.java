package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which routes carry OpenAI's {@code error} object, and what it says. That it
 * reaches the wire on each route, from a controller and from a security
 * filter, is in the routes' integration tests ({@code ImageEditsIntegrationTest},
 * {@code ImageGenerationIntegrationTest}, {@code OpenAiVideoIntegrationTest}).
 */
class OpenAiErrorTest {

    private static final JsonMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    @ParameterizedTest
    @ValueSource(strings = {"/v1/images/generations", "/v1/images/edits", "/v1/images/variations",
            "/v1/videos", "/v1/videos/video_123", "/v1/videos/video_123/content", "/v1/videos/characters/c_1"})
    void appliesTo_givenOpenAisImageAndVideoRoutes_expectsTrue(String path) {
        assertThat(OpenAiError.appliesTo(path)).isTrue();
        assertThat(OpenAiError.appliesTo(MockServerWebExchange.from(MockServerHttpRequest.post(path)))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/videos/generations", "/v1/videos/generations/x",
            "/v1/images", "/v1/images/", "/v1/images/variations/", "/v1/images/edits/", "/v1/images/edits/x",
            "/v1/images/generations/", "/v1/imagesx/edits", "/v1/videosx",
            "/v1/jobs", "/v1/jobs/abc", "/v1/files", "/v1/chat/completions", "/v1/models", "/v1/estimates"})
    void appliesTo_givenEveryOtherPath_expectsFalse(String path) {
        assertThat(OpenAiError.appliesTo(path)).isFalse();
    }

    @Test
    void addTo_givenARefusalWithCodeAndParam_expectsTheObjectBesideEveryMemberItHad() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "POST /v1/images/edits does not take output_format.");
        problem.setProperty("code", "invalid_parameter");
        problem.setProperty("param", "output_format");

        OpenAiError.addTo(problem);

        assertThat(problem.getDetail()).isEqualTo("POST /v1/images/edits does not take output_format.");
        assertThat(problem.getProperties()).containsEntry("code", "invalid_parameter")
                .containsEntry("param", "output_format");
        assertThat(problem.getProperties().get("error")).isEqualTo(Map.of(
                "message", "POST /v1/images/edits does not take output_format.",
                "type", "invalid_request_error",
                "param", "output_format",
                "code", "invalid_parameter"));
    }

    @Test
    void addTo_givenNoDetailCodeOrParam_expectsTheTitleAsTheMessageAndNulls() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        // A field's name kept in "field" is not OpenAI's param.
        problem.setProperty("field", "mask");

        OpenAiError.addTo(problem);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) problem.getProperties().get("error");
        assertThat(error).containsEntry("message", "Not Found").containsEntry("type", "not_found_error")
                .containsEntry("param", null).containsEntry("code", null);
        assertThat(error.keySet()).containsExactly("message", "type", "param", "code");
    }

    @Test
    void addTo_givenAProblemThatAlreadyHasTheObject_expectsItLeftAsItIs() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "new words");
        Map<String, Object> own = Map.of("message", "own words", "type", "invalid_request_error");
        problem.setProperty("error", own);

        OpenAiError.addTo(problem);

        assertThat(problem.getProperties().get("error")).isSameAs(own);
    }

    @Test
    void type_givenEachStatus_expectsOpenAisType() {
        assertThat(OpenAiError.type(400)).isEqualTo("invalid_request_error");
        assertThat(OpenAiError.type(401)).isEqualTo("authentication_error");
        assertThat(OpenAiError.type(402)).isEqualTo("invalid_request_error");
        assertThat(OpenAiError.type(403)).isEqualTo("permission_error");
        assertThat(OpenAiError.type(404)).isEqualTo("not_found_error");
        assertThat(OpenAiError.type(413)).isEqualTo("invalid_request_error");
        assertThat(OpenAiError.type(429)).isEqualTo("rate_limit_error");
        assertThat(OpenAiError.type(502)).isEqualTo("server_error");
        assertThat(OpenAiError.type(503)).isEqualTo("server_error");
    }

    @Test
    void problemResponse_givenAFilterRefusalOnAnImageRoute_expectsTheObjectInTheBodyItWrites() {
        MockServerWebExchange edits = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/images/edits"));
        ProblemResponse.write(edits, HttpStatus.UNAUTHORIZED, "invalid API key").block();

        JsonNode body = JSON.readTree(edits.getResponse().getBodyAsString().block());
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("detail").asString()).isEqualTo("invalid API key");
        JsonNode error = body.get("error");
        assertThat(error.get("message").asString()).isEqualTo("invalid API key");
        assertThat(error.get("type").asString()).isEqualTo("authentication_error");
        assertThat(error.get("param").isNull()).isTrue();
        assertThat(error.get("code").isNull()).isTrue();

        // A code the filter gives is the object's code too; another route's body is as it was.
        MockServerWebExchange generations = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/images/generations"));
        ProblemResponse.write(generations, HttpStatus.FORBIDDEN, "not here", "some_code").block();
        assertThat(JSON.readTree(generations.getResponse().getBodyAsString().block())
                .get("error").get("code").asString()).isEqualTo("some_code");

        MockServerWebExchange files = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/files"));
        ProblemResponse.write(files, HttpStatus.UNAUTHORIZED, "invalid API key").block();
        assertThat(JSON.readTree(files.getResponse().getBodyAsString().block()).has("error")).isFalse();
    }
}
