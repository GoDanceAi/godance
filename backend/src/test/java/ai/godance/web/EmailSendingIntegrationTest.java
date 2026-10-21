package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.Modality;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.SpendingLimitService;
import ai.godance.service.mail.ResendEmailSender;
import ai.godance.service.pricing.PricingRefreshService;

/**
 * End-to-end email sending against a WireMock Resend stand-in (a real —
 * non-dummy — API key is injected, so the {@code ResendEmailSender} is
 * active):
 *
 * <ul>
 *   <li>signup → exactly one verify email, request shape pinned;</li>
 *   <li>80% cap crossing → one warning, not one per request;</li>
 *   <li>pricing digest → sent only when proposals are pending (the
 *       empty-allowlist half of the gate is covered in
 *       {@code MailServiceTest} — the allowlist here is static);</li>
 *   <li>Resend 5xx → signup still succeeds, {@code mail.send.failed}
 *       audited.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class EmailSendingIntegrationTest {

    private static final String TEST_API_KEY = "re_test_integration_key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    /** One WireMock doubles as Resend (/emails) and both pricing feeds. */
    static WireMockServer wm;

    @BeforeAll
    static void startMock() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopMock() {
        if (wm != null) wm.stop();
    }

    @DynamicPropertySource
    static void mailProps(DynamicPropertyRegistry r) {
        r.add("godance.mail.api-key",  () -> TEST_API_KEY);
        r.add("godance.mail.api-base", () -> wm.baseUrl());
        r.add("godance.admin.emails",  () -> "admin-one@godance.test,admin-two@godance.test");
        // Empty feeds: the digest tests drive PricingRefreshService.runOnce()
        // directly; feed content is PricingRefreshFlowIntegrationTest's concern.
        r.add("godance.pricing.feeds.openrouter-url", () -> wm.baseUrl() + "/or/models");
        r.add("godance.pricing.feeds.litellm-url",    () -> wm.baseUrl() + "/litellm.json");
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired ModelChangeProposalRepository proposals;
    @Autowired AuditLogEntryRepository auditEntries;
    @Autowired SpendingLimitService spendingLimits;
    @Autowired PricingRefreshService refresh;

    @BeforeEach
    void reset() {
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/emails"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\": \"" + UUID.randomUUID() + "\"}")));
        wm.stubFor(get(urlPathEqualTo("/or/models"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\": []}")));
        wm.stubFor(get(urlPathEqualTo("/litellm.json"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{}")));
        proposals.deleteAll();
    }

    private String signup(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Email Tester"))
                .exchange()
                .expectStatus().isCreated();
        return email;
    }

    // ===== signup → verify-email ======================================

    @Test
    void signup_givenResendUp_expectsExactlyOneVerifyEmailWithPinnedShape() {
        String email = signup("verify-" + UUID.randomUUID() + "@godance.test");
        User u = users.findByEmail(email).orElseThrow();

        wm.verify(1, postRequestedFor(urlEqualTo("/emails")));
        wm.verify(1, postRequestedFor(urlEqualTo("/emails"))
                .withHeader("Authorization", equalTo("Bearer " + TEST_API_KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.from", equalTo("Godance <noreply@godance.ai>")))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo(email)))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Verify your Godance email")))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("/verify-email?token=" + u.getEmailVerificationToken())))
                .withRequestBody(matchingJsonPath("$.html", containing("verify"))));
    }

    @Test
    void signup_givenResend500_expectsSignupStillCreated_andMailSendFailedAudited() {
        wm.stubFor(post(urlEqualTo("/emails"))
                .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"boom\"}")));

        signup("fail-" + UUID.randomUUID() + "@godance.test");   // asserts 201 inside

        assertThat(auditEntries.findByEventType(ResendEmailSender.EVT_MAIL_SEND_FAILED)).isNotEmpty();
    }

    // ===== 80% soft cap → spend-warning-80 ============================

    @Test
    void enforce_givenSoftCapCrossedTwice_expectsOneWarningEmailNotTwo() {
        String email = signup("spend-" + UUID.randomUUID() + "@godance.test");
        User u = users.findByEmail(email).orElseThrow();
        u.setDailyCreditCapCents(100_00L);
        users.save(u);
        seedUsage(u.getId(), 85_00L);

        spendingLimits.enforce(u.getId(), null);
        spendingLimits.enforce(u.getId(), null);   // same period — must not re-warn

        int warnings = wm.countRequestsMatching(postRequestedFor(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.subject", containing("spending cap")))
                .build()).getCount();
        assertThat(warnings).isEqualTo(1);
        wm.verify(1, postRequestedFor(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.subject", containing("spending cap")))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo(email))));
    }

    // ===== pricing digest =============================================

    @Test
    void runOnce_givenPendingProposal_expectsOneDigestToWholeAdminAllowlist() {
        seedPendingProposal("gpt-4o");

        refresh.runOnce();

        wm.verify(1, postRequestedFor(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.subject", containing("pricing digest")))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo("admin-one@godance.test")))
                .withRequestBody(matchingJsonPath("$.to[1]", equalTo("admin-two@godance.test")))
                .withRequestBody(matchingJsonPath("$.text", containing("gpt-4o"))));
    }

    @Test
    void runOnce_givenNothingPending_expectsNoDigest() {
        refresh.runOnce();

        int digests = wm.countRequestsMatching(postRequestedFor(urlEqualTo("/emails"))
                .withRequestBody(matchingJsonPath("$.subject", containing("pricing digest")))
                .build()).getCount();
        assertThat(digests).isZero();
    }

    // ===== helpers ====================================================

    private void seedUsage(UUID userId, long costCents) {
        UsageRecord record = new UsageRecord();
        record.setUserId(userId);
        record.setModality(Modality.TEXT);
        record.setModelName("gpt-4o");
        record.setProvider(Provider.OPENAI);
        record.setInputTokens(0);
        record.setOutputTokens(0);
        record.setCostCents(costCents);
        record.setRegion(Region.LOCAL);
        record.setCacheHit(false);
        record.setFallbackUsed(false);
        record.setRequestId("seed-" + UUID.randomUUID());
        record.setRecordedAt(Instant.now());
        usage.save(record);
    }

    private void seedPendingProposal(String modelName) {
        ModelChangeProposal p = new ModelChangeProposal();
        p.setType(ProposalType.PRICE_CHANGE);
        p.setModelName(modelName);
        p.setProvider(Provider.OPENAI);
        p.setModality(Modality.TEXT);
        p.setPayload(Map.of("field", "input_microcents_per_1k",
                "old_value", 100_000, "new_value", 150_000));
        p.setSource("test");
        p.setDetectedAt(Instant.now());
        p.setStatus(ProposalStatus.PENDING);
        proposals.save(p);
    }
}
