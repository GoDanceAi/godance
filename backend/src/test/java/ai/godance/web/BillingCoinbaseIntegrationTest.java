package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.BillingDtos.TopUpResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class BillingCoinbaseIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "cb_test_webhook_secret_value_for_integration";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer coinbaseMock;

    @BeforeAll
    static void startMock() {
        coinbaseMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        coinbaseMock.start();
    }

    @AfterAll
    static void stopMock() {
        if (coinbaseMock != null) coinbaseMock.stop();
    }

    @DynamicPropertySource
    static void coinbaseProps(DynamicPropertyRegistry r) {
        r.add("godance.coinbase.api-base",       () -> coinbaseMock.baseUrl());
        r.add("godance.coinbase.webhook-secret", () -> TEST_WEBHOOK_SECRET);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledger;

    @BeforeEach
    void reset() {
        coinbaseMock.resetAll();
    }

    // ===== Helpers ====================================================

    private record SignedUser(UUID id, String bearer) { }

    private SignedUser signupAndAuth() {
        String email = "cb-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Coinbase Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        return new SignedUser(userId, "Bearer " + resp.accessToken());
    }

    private static String coinbaseSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_WEBHOOK_SECRET.getBytes(), "HmacSHA256"));
            byte[] h = mac.doFinal(payload.getBytes());
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String chargeConfirmedEvent(String eventId, String chargeId,
                                               UUID userId, String amountUsd) {
        return """
            {
              "id": "%s",
              "scheduled_for": "2025-01-01T00:00:00Z",
              "event": {
                "id": "%s",
                "resource": "event",
                "type": "charge:confirmed",
                "api_version": "2018-03-22",
                "created_at": "2025-01-01T00:00:00Z",
                "data": {
                  "id": "%s",
                  "code": "ABC12345",
                  "resource": "charge",
                  "pricing": {
                    "local": {"amount": "%s", "currency": "USD"}
                  },
                  "metadata": {"godance_user_id": "%s"},
                  "timeline": []
                }
              }
            }
            """.formatted(eventId, eventId, chargeId, amountUsd, userId);
    }

    private static String chargePendingEvent(String eventId, UUID userId) {
        return """
            {
              "id": "%s",
              "event": {
                "id": "%s",
                "type": "charge:pending",
                "data": {
                  "id": "ch_pending",
                  "metadata": {"godance_user_id": "%s"},
                  "pricing": {"local": {"amount": "1.00", "currency": "USD"}}
                }
              }
            }
            """.formatted(eventId, eventId, userId);
    }

    // ===== Webhook tests ==============================================

    @Test
    void webhook_givenValidSignedConfirmed_creditsLedgerAndBalance() {
        SignedUser u = signupAndAuth();
        String payload = chargeConfirmedEvent("evt_cb_apply", "ch_cb_apply", u.id(), "25.00");

        http.post().uri("/v1/billing/webhooks/coinbase")
                .header("X-CC-Webhook-Signature", coinbaseSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(25_00L);
        assertThat(ledger.findByIdempotencyKey("evt_cb_apply")).isPresent();
    }

    @Test
    void webhook_replayedSameEvent_doesNotDoubleCredit() {
        SignedUser u = signupAndAuth();
        String payload = chargeConfirmedEvent("evt_cb_dup", "ch_cb_dup", u.id(), "10.00");
        String sig = coinbaseSignature(payload);

        // First delivery
        http.post().uri("/v1/billing/webhooks/coinbase")
                .header("X-CC-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        // Replay — same body, same signature (Coinbase HMAC has no timestamp,
        // so retries always carry the identical signature byte-for-byte).
        http.post().uri("/v1/billing/webhooks/coinbase")
                .header("X-CC-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10_00L);
    }

    @Test
    void webhook_givenBadSignature_returns400_doesNotMutateBalance() {
        SignedUser u = signupAndAuth();
        String payload = chargeConfirmedEvent("evt_cb_bad", "ch_cb_bad", u.id(), "5.00");

        http.post().uri("/v1/billing/webhooks/coinbase")
                .header("X-CC-Webhook-Signature", "deadbeef00not_a_real_hex_signature")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
    }

    @Test
    void webhook_givenChargePending_returns200_doesNotCredit() {
        SignedUser u = signupAndAuth();
        String payload = chargePendingEvent("evt_cb_pending", u.id());

        http.post().uri("/v1/billing/webhooks/coinbase")
                .header("X-CC-Webhook-Signature", coinbaseSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
        assertThat(ledger.findByIdempotencyKey("evt_cb_pending")).isNotPresent();
    }

    @Test
    void webhook_givenNonUsdCurrency_returns500_doesNotCredit() {
        SignedUser u = signupAndAuth();
        String payload = """
            {
              "id": "evt_cb_eur",
              "event": {
                "id": "evt_cb_eur",
                "type": "charge:confirmed",
                "data": {
                  "id": "ch_eur",
                  "pricing": {"local": {"amount": "25.00", "currency": "EUR"}},
                  "metadata": {"godance_user_id": "%s"}
                }
              }
            }
            """.formatted(u.id());

        // IllegalArgumentException from usdAmountCents() surfaces as 500.
        // Coinbase will retry — that's correct behavior; the misconfigured
        // charge shouldn't have been created in the first place.
        http.post().uri("/v1/billing/webhooks/coinbase")
                .header("X-CC-Webhook-Signature", coinbaseSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().is5xxServerError();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
    }

    // ===== Charge create tests ========================================

    @Test
    void topup_authenticated_returnsHostedUrl() {
        SignedUser u = signupAndAuth();

        coinbaseMock.stubFor(post(urlEqualTo("/charges"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"data":{"id":"ch_test_xyz","code":"ABCDEFGH",
                                 "hosted_url":"https://commerce.coinbase.com/charges/ABCDEFGH",
                                 "resource":"charge"}}
                                """)));

        TopUpResponse out = http.post().uri("/v1/billing/coinbase/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 25_00L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TopUpResponse.class).returnResult().getResponseBody();

        assertThat(out.sessionId()).isEqualTo("ch_test_xyz");
        assertThat(out.checkoutUrl()).isEqualTo("https://commerce.coinbase.com/charges/ABCDEFGH");
        assertThat(out.amountCents()).isEqualTo(25_00L);
    }

    @Test
    void topup_amountBelowFloor_returns400() {
        SignedUser u = signupAndAuth();

        http.post().uri("/v1/billing/coinbase/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 50L))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void topup_unauthenticated_returns401() {
        http.post().uri("/v1/billing/coinbase/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 10_00L))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void topup_upstreamCoinbase500_returns502() {
        SignedUser u = signupAndAuth();

        coinbaseMock.stubFor(post(urlEqualTo("/charges"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"type\":\"internal_server_error\"}}")));

        http.post().uri("/v1/billing/coinbase/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 10_00L))
                .exchange()
                .expectStatus().isEqualTo(502);
    }
}
