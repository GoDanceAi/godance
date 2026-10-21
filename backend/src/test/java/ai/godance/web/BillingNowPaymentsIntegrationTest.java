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
import ai.godance.service.NowPaymentsCanonicalJson;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.BillingDtos.TopUpResponse;

/**
 * Integration coverage for the NOWPayments crypto top-up rail: invoice
 * creation against a WireMock NOWPayments API, and the IPN webhook —
 * proving (a) a signed {@code finished} IPN credits once, (b) a replay
 * is deduped, (c) a bad signature is rejected with 400, (d) a
 * non-terminal status does not credit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class BillingNowPaymentsIntegrationTest {

    private static final String TEST_IPN_SECRET = "np_test_ipn_secret_value_for_integration";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer nowPaymentsMock;

    @BeforeAll
    static void startMock() {
        nowPaymentsMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        nowPaymentsMock.start();
    }

    @AfterAll
    static void stopMock() {
        if (nowPaymentsMock != null) nowPaymentsMock.stop();
    }

    @DynamicPropertySource
    static void nowPaymentsProps(DynamicPropertyRegistry r) {
        r.add("godance.nowpayments.api-base",   () -> nowPaymentsMock.baseUrl());
        r.add("godance.nowpayments.ipn-secret", () -> TEST_IPN_SECRET);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledger;

    @BeforeEach
    void reset() {
        nowPaymentsMock.resetAll();
    }

    // ===== Helpers ====================================================

    private record SignedUser(UUID id, String bearer) { }

    private SignedUser signupAndAuth() {
        String email = "np-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "NOWPayments Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        return new SignedUser(userId, "Bearer " + resp.accessToken());
    }

    /**
     * HMAC-SHA512 hex over the canonical form — the scheme NOWPayments uses.
     * Reuses the production canonicaliser: these tests cover the webhook
     * flow, while {@code NowPaymentsServiceTest} pins the canonical form
     * against signatures from NOWPayments' own JS reference code.
     */
    private static String nowPaymentsSignature(String payload) {
        try {
            String canonical = NowPaymentsCanonicalJson.canonicalise(payload);
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(TEST_IPN_SECRET.getBytes(), "HmacSHA512"));
            byte[] h = mac.doFinal(canonical.getBytes());
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String ipnEvent(String paymentId, String status, UUID userId, String priceAmount) {
        return """
            {
              "payment_id": %s,
              "payment_status": "%s",
              "pay_address": "TXkV21abc000example000address000000000",
              "price_amount": %s,
              "price_currency": "usd",
              "pay_amount": 24.9,
              "pay_currency": "usdttrc20",
              "order_id": "%s",
              "order_description": "Godance credit top-up"
            }
            """.formatted(paymentId, status, priceAmount, userId);
    }

    // ===== Webhook tests ==============================================

    @Test
    void webhook_givenValidSignedFinished_creditsLedgerAndBalance() {
        SignedUser u = signupAndAuth();
        String payload = ipnEvent("5500000001", "finished", u.id(), "25");

        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig", nowPaymentsSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(25_00L);
        assertThat(ledger.findByIdempotencyKey("5500000001")).isPresent();
    }

    @Test
    void webhook_replayedSameEvent_doesNotDoubleCredit() {
        SignedUser u = signupAndAuth();
        String payload = ipnEvent("5500000002", "finished", u.id(), "10");
        String sig = nowPaymentsSignature(payload);

        // First delivery.
        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        // Replay — identical body + signature. NOWPayments re-delivers an IPN
        // until it gets a 2xx, so retries carry the same payment_id.
        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10_00L);
    }

    @Test
    void webhook_givenBadSignature_returns400_doesNotMutateBalance() {
        SignedUser u = signupAndAuth();
        String payload = ipnEvent("5500000003", "finished", u.id(), "5");

        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig", "deadbeef00not_a_real_hex_signature")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
        assertThat(ledger.findByIdempotencyKey("5500000003")).isNotPresent();
    }

    @Test
    void webhook_givenNonTerminalStatus_returns200_doesNotCredit() {
        SignedUser u = signupAndAuth();
        String payload = ipnEvent("5500000004", "waiting", u.id(), "25");

        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig", nowPaymentsSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
        assertThat(ledger.findByIdempotencyKey("5500000004")).isNotPresent();
    }

    @Test
    void webhook_givenPartiallyPaidStatus_returns200_doesNotCredit() {
        SignedUser u = signupAndAuth();
        String payload = ipnEvent("5500000005", "partially_paid", u.id(), "25");

        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig", nowPaymentsSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
    }

    // ===== Invoice create tests =======================================

    @Test
    void topup_authenticated_returnsHostedUrl() {
        SignedUser u = signupAndAuth();

        nowPaymentsMock.stubFor(post(urlEqualTo("/v1/invoice"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":4901234567,"order_id":"%s","price_amount":25,
                                 "price_currency":"usd","invoice_status":"waiting",
                                 "invoice_url":"https://nowpayments.io/payment/?iid=4901234567"}
                                """.formatted(u.id()))));

        TopUpResponse out = http.post().uri("/v1/billing/nowpayments/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 25_00L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TopUpResponse.class).returnResult().getResponseBody();

        assertThat(out.sessionId()).isEqualTo("4901234567");
        assertThat(out.checkoutUrl()).isEqualTo("https://nowpayments.io/payment/?iid=4901234567");
        assertThat(out.amountCents()).isEqualTo(25_00L);
    }

    @Test
    void topup_amountBelowFloor_returns400() {
        SignedUser u = signupAndAuth();

        http.post().uri("/v1/billing/nowpayments/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 50L))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void topup_unauthenticated_returns401() {
        http.post().uri("/v1/billing/nowpayments/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 10_00L))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void topup_upstreamNowPayments500_returns502() {
        SignedUser u = signupAndAuth();

        nowPaymentsMock.stubFor(post(urlEqualTo("/v1/invoice"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"message\":\"internal error\"}")));

        http.post().uri("/v1/billing/nowpayments/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 10_00L))
                .exchange()
                .expectStatus().isEqualTo(502);
    }
}
