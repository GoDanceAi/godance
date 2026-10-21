package ai.godance.service.mail;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AuditOutcome;
import ai.godance.service.AuditLogService;

/**
 * Pins the Resend request shape against a WireMock stand-in and proves
 * the failure-containment contract: non-2xx and transport errors are
 * swallowed + audited as {@code mail.send.failed}, never thrown.
 */
class ResendEmailSenderTest {

    private static final String API_KEY = "re_test_key_for_unit_tests";
    private static final String FROM = "Godance <noreply@godance.ai>";

    static WireMockServer resend;

    AuditLogService audit;
    ResendEmailSender sender;

    @BeforeAll
    static void startMock() {
        resend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        resend.start();
    }

    @AfterAll
    static void stopMock() {
        if (resend != null) resend.stop();
    }

    @BeforeEach
    void setUp() {
        resend.resetAll();
        audit = mock(AuditLogService.class);
        sender = new ResendEmailSender(WebClient.builder(),
                new MailProperties(API_KEY, FROM, resend.baseUrl(), "https://app.godance.test"), audit);
    }

    @Test
    void send_givenSuccess_postsResendShape_andDoesNotAudit() {
        resend.stubFor(post(urlEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\": \"4ef0071a-1a5e-4ab4-8b06-test\"}")));

        sender.send(List.of("user@godance.test"), "Verify your Godance email",
                "text body", "<p>html body</p>");

        resend.verify(1, postRequestedFor(urlEqualTo("/emails"))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {
                          "from": "Godance <noreply@godance.ai>",
                          "to": ["user@godance.test"],
                          "subject": "Verify your Godance email",
                          "text": "text body",
                          "html": "<p>html body</p>"
                        }
                        """)));
        verifyNoInteractions(audit);
    }

    @Test
    void send_givenNon2xx_swallowsAndAuditsMailSendFailed() {
        resend.stubFor(post(urlEqualTo("/emails"))
                .willReturn(aResponse().withStatus(422)
                        .withBody("{\"message\": \"domain not verified\"}")));

        assertThatCode(() -> sender.send(List.of("user@godance.test"),
                "Verify your Godance email", "text", "<p>html</p>"))
                .doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ResendEmailSender.EVT_MAIL_SEND_FAILED), eq(AuditOutcome.FAILURE),
                isNull(), isNull(), detail.capture());
        org.assertj.core.api.Assertions.assertThat(detail.getValue())
                .containsEntry("to", "user@godance.test")
                .containsEntry("subject", "Verify your Godance email");
        org.assertj.core.api.Assertions.assertThat((String) detail.getValue().get("reason"))
                .contains("status=422");
    }

    @Test
    void send_givenConnectionFailure_swallowsAndAuditsMailSendFailed() {
        // Point at a port nothing listens on.
        ResendEmailSender dead = new ResendEmailSender(WebClient.builder(),
                new MailProperties(API_KEY, FROM, "http://127.0.0.1:1", "https://app.godance.test"), audit);

        assertThatCode(() -> dead.send(List.of("user@godance.test"), "subject", "text", "html"))
                .doesNotThrowAnyException();

        verify(audit).record(eq(ResendEmailSender.EVT_MAIL_SEND_FAILED), eq(AuditOutcome.FAILURE),
                isNull(), isNull(), any());
    }
}
