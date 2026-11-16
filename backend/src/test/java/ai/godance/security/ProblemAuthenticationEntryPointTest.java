package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the entry point writes for each way a request fails to authenticate.
 * That it is the one the API chain uses, on every route, is in the routes'
 * integration tests ({@code AuthAndApiKeyIntegrationTest},
 * {@code ImageGenerationIntegrationTest}, {@code OpenAiVideoIntegrationTest}).
 */
class ProblemAuthenticationEntryPointTest {

    private static final JsonMapper JSON = JsonMapper.builderWithJackson2Defaults().build();
    /** What Spring's JWT decoder says about an OpenAI key, word for word. */
    private static final String DECODER_SAYS = "An error occurred while attempting to decode the Jwt: "
            + "Invalid JWT serialization: Missing dot delimiter(s)";

    private final ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();

    @Test
    void commence_givenNoCredential_expects401ProblemAndABareBearerChallenge() {
        MockServerWebExchange jobs = commence("/v1/jobs",
                new AuthenticationCredentialsNotFoundException("Not Authenticated"));

        assertThat(jobs.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jobs.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(jobs.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = body(jobs);
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("detail").asString()).isEqualTo("This request has no API key or session. "
                + "Send a Godance API key as Authorization: Bearer gd_live_…, or sign in.");
        assertThat(body.has("code")).isFalse();
        assertThat(body.has("error")).as("/v1/jobs does not follow OpenAI's API").isFalse();
    }

    @Test
    void commence_givenATokenTheDecoderRefused_expects401ProblemWithOurSentenceAndSpringsChallenge() {
        MockServerWebExchange images = commence("/v1/images/generations", new InvalidBearerTokenException(DECODER_SAYS));

        assertThat(images.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The header is Spring's, the decoder's words included, as RFC 6750 has it.
        assertThat(images.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\", error_description=\"" + DECODER_SAYS
                        + "\", error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\"");
        String raw = images.getResponse().getBodyAsString().block();
        assertThat(raw).doesNotContain("decode").doesNotContain("JWT serialization");
        JsonNode body = JSON.readTree(raw);
        assertThat(body.get("detail").asString()).isEqualTo(
                "The bearer token is not a Godance API key or a current session. Godance API keys start with gd_live_.");
        // An OpenAI route: OpenAI's error object, with the same sentence.
        JsonNode error = body.get("error");
        assertThat(error.get("message").asString()).isEqualTo(body.get("detail").asString());
        assertThat(error.get("type").asString()).isEqualTo("authentication_error");
        assertThat(error.get("param").isNull()).isTrue();
        assertThat(error.get("code").isNull()).isTrue();
    }

    @Test
    void commence_givenTwoBearerTokens_expectsSpringsStatus400AndItsChallenge() {
        MockServerWebExchange videos = commence("/v1/videos", new OAuth2AuthenticationException(
                BearerTokenErrors.invalidRequest("Found multiple bearer tokens in the request")));

        assertThat(videos.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(videos.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer error=\"invalid_request\"");
        JsonNode body = body(videos);
        assertThat(body.get("title").asString()).isEqualTo("Bad Request");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("detail").asString()).isEqualTo(
                "The bearer token is empty or sent more than once. Send one, in the Authorization header.");
        assertThat(body.get("error").get("type").asString()).isEqualTo("invalid_request_error");
    }

    @Test
    void detail_givenAnyOtherRefusalOfTheToken_expectsAPlainSentence() {
        assertThat(ProblemAuthenticationEntryPoint.detail(new OAuth2AuthenticationException(
                BearerTokenErrors.insufficientScope("needs more", "write"))))
                .isEqualTo("The bearer token was refused.");
    }

    private MockServerWebExchange commence(String path, AuthenticationException failure) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
        entryPoint.commence(exchange, failure).block();
        assertThat(exchange.getResponse().isCommitted()).isTrue();
        return exchange;
    }

    private static JsonNode body(MockServerWebExchange exchange) {
        return JSON.readTree(exchange.getResponse().getBodyAsString().block());
    }
}
