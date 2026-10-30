package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Request;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.mail.LoggingEmailSender;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.BillingDtos.TopUpResponse;
import ai.godance.web.dto.ParentalConsentDtos.ParentalConsentView;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Verifiable parental consent end to end: signup with a birth month, the
 * restricted {@code PENDING_CONSENT} session, the parent's public consent
 * page and Stripe Checkout (on WireMock), and the signed Stripe webhook
 * that grants consent once, and only once, the parent's payment is paid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ParentalConsentIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_parental_consent_secret_value";
    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String EVT_COMPLETED       = "checkout.session.completed";
    private static final String EVT_ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";
    private static final String EVT_ASYNC_FAILED    = "checkout.session.async_payment_failed";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer stripeMock;

    @BeforeAll
    static void startStripeMock() {
        stripeMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripeMock.start();
    }

    @AfterAll
    static void stopStripeMock() {
        if (stripeMock != null) stripeMock.stop();
    }

    @DynamicPropertySource
    static void stripeProps(DynamicPropertyRegistry r) {
        r.add("godance.stripe.api-base",       () -> stripeMock.baseUrl());
        r.add("godance.stripe.webhook-secret", () -> TEST_WEBHOOK_SECRET);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audits;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetStubs() {
        stripeMock.resetAll();
    }

    // ===== Helpers ====================================================

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    /** The {@code YYYY-MM} of someone {@code years} old (or just turned) this month. */
    private static String birthMonthYearsAgo(int years) {
        return YearMonth.now(ZoneOffset.UTC).minusYears(years).toString();
    }

    private WebTestClient.ResponseSpec signup(String email, String birthMonth, String parentEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("fullName", "Kim Lee");
        if (birthMonth != null) body.put("birthMonth", birthMonth);
        if (parentEmail != null) body.put("parentEmail", parentEmail);
        return http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** Runs {@code action} and returns the first lines the [email-stub] sender logged meanwhile. */
    private static List<String> emailsDuring(Runnable action) {
        Logger stubLog = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stubLog.addAppender(appender);
        try {
            action.run();
        } finally {
            stubLog.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The consent token in the email to {@code parentEmail}, as the parent's link carries it. */
    private static String consentToken(List<String> emails, String parentEmail) {
        Pattern link = Pattern.compile("Parental consent URL for " + Pattern.quote(parentEmail)
                + ": \\S+/parent-consent\\?token=(\\S+)");
        return emails.stream()
                .map(link::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no consent email logged for " + parentEmail));
    }

    private record Child(UUID id, String email, String parentEmail, String bearer, String token) { }

    /** A 12-year-old's signup; returns the restricted session and the token emailed to the parent. */
    private Child signupChild(String tag) {
        String email = uniqueEmail(tag);
        String parentEmail = uniqueEmail(tag + "-parent");
        AuthResponse[] resp = new AuthResponse[1];
        List<String> emails = emailsDuring(() -> resp[0] = signup(email, birthMonthYearsAgo(12), parentEmail)
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody());
        UUID id = users.findByEmail(email).orElseThrow().getId();
        return new Child(id, email, parentEmail, "Bearer " + resp[0].accessToken(),
                consentToken(emails, parentEmail));
    }

    private String login(String email) {
        return "Bearer " + http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody().accessToken();
    }

    private MeResponse me(String bearer) {
        return http.get().uri("/v1/auth/me").header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MeResponse.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec consentView(String token) {
        return http.get().uri("/v1/auth/parental-consent?token={t}", token).exchange();
    }

    private User reload(UUID id) {
        return users.findById(id).orElseThrow();
    }

    private List<AuditLogEntry> auditFor(String eventType, UUID userId) {
        return audits.findByEventType(eventType).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    // ----- Stripe webhook --------------------------------------------

    private static String stripeSignature(String payload) {
        long t = Instant.now().getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_WEBHOOK_SECRET.getBytes(), "HmacSHA256"));
            return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Checkout Session event as Stripe sends it; {@code consent} adds the parental-consent metadata. */
    private static String checkoutEvent(String type, String eventId, String sessionId, UUID userId,
                                        long amountCents, String paymentStatus, boolean consent) {
        String metadata = consent
                ? "{\"godance_user_id\": \"%s\", \"godance_purpose\": \"parental_consent\"}".formatted(userId)
                : "{\"godance_user_id\": \"%s\"}".formatted(userId);
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "2024-12-18.acacia",
              "created": %d,
              "livemode": false,
              "pending_webhooks": 1,
              "request": {"id": null, "idempotency_key": null},
              "type": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "checkout.session",
                  "amount_total": %d,
                  "currency": "usd",
                  "client_reference_id": "%s",
                  "customer_email": "parent@family.test",
                  "metadata": %s,
                  "mode": "payment",
                  "payment_status": "%s",
                  "status": "complete",
                  "livemode": false
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), type, sessionId, amountCents,
                          userId, metadata, paymentStatus);
    }

    private void deliver(String payload) {
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    private static boolean stripeCalled(String urlPrefix) {
        return stripeMock.getAllServeEvents().stream()
                .anyMatch(e -> e.getRequest().getUrl().startsWith(urlPrefix));
    }

    // ===== Signup =====================================================

    @Test
    void signup_givenAdultBirthMonth_expectsFullAccessAndVerificationEmail() {
        String email = uniqueEmail("adult");
        AuthResponse[] resp = new AuthResponse[1];
        List<String> emails = emailsDuring(() -> resp[0] = signup(email, birthMonthYearsAgo(30), null)
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody());
        String bearer = "Bearer " + resp[0].accessToken();

        MeResponse me = me(bearer);
        assertThat(me.ageStatus()).isEqualTo(AgeStatus.ADULT);
        assertThat(me.parentEmailMasked()).isNull();
        http.get().uri("/v1/keys").header("Authorization", bearer).exchange().expectStatus().isOk();
        User u = reload(me.id());
        assertThat(u.getBirthMonth()).isEqualTo(YearMonth.now(ZoneOffset.UTC).minusYears(30).atDay(1));
        assertThat(u.getParentEmail()).isNull();
        assertThat(emails).anyMatch(line -> line.contains("Verification URL for " + email));
    }

    @Test
    void signup_givenAdultWithParentEmail_ignoresIt() {
        String email = uniqueEmail("adult-parent");
        signup(email, birthMonthYearsAgo(40), uniqueEmail("unneeded")).expectStatus().isCreated();

        User u = users.findByEmail(email).orElseThrow();
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.ADULT);
        assertThat(u.getParentEmail()).isNull();
    }

    /** API clients and existing integrations: no birth month means an adult attesting by the Terms. */
    @Test
    void signup_givenNoBirthMonth_expectsAdultWithFullAccess() {
        String email = uniqueEmail("no-birth-month");
        String bearer = "Bearer " + signup(email, null, null)
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody().accessToken();

        assertThat(me(bearer).ageStatus()).isEqualTo(AgeStatus.ADULT);
        assertThat(users.findByEmail(email).orElseThrow().getBirthMonth()).isNull();
        http.get().uri("/v1/keys").header("Authorization", bearer).exchange().expectStatus().isOk();
    }

    /** A row from before V19, written without the new columns, is an adult's. */
    @Test
    void migration_givenUserRowWithoutAgeColumns_expectsAdult() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, full_name, role) VALUES (?, ?, 'Old Timer', 'USER')",
                id, uniqueEmail("pre-v19"));

        User u = reload(id);
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.ADULT);
        assertThat(u.getBirthMonth()).isNull();
    }

    @Test
    void signup_givenUnder16WithoutParentEmail_returns400AndCreatesNoAccount() {
        String email = uniqueEmail("kid-no-parent");
        signup(email, birthMonthYearsAgo(12), null)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(d ->
                        assertThat((String) d).contains("parent's or guardian's email"));

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void signup_givenUnder16WithOwnEmailAsParents_returns400() {
        String email = uniqueEmail("kid-own");
        signup(email, birthMonthYearsAgo(12), email.toUpperCase()).expectStatus().isBadRequest();

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void signup_givenFutureOrMalformedBirthMonth_returns400() {
        signup(uniqueEmail("future"), YearMonth.now(ZoneOffset.UTC).plusMonths(2).toString(), null)
                .expectStatus().isBadRequest();
        signup(uniqueEmail("month-13"), "2010-13", null).expectStatus().isBadRequest();
        signup(uniqueEmail("no-dash"), "201005", null).expectStatus().isBadRequest();
    }

    /** Someone whose 16th birthday falls this month may not have had it yet. */
    @Test
    void signup_givenSixteenthBirthdayThisMonth_expectsConsentPending() {
        String email = uniqueEmail("sixteen-this-month");
        signup(email, birthMonthYearsAgo(16), uniqueEmail("parent")).expectStatus().isCreated();

        assertThat(users.findByEmail(email).orElseThrow().getAgeStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
    }

    @Test
    void signup_givenUnder16_expectsPendingAccountAndEmailToParentOnly() throws Exception {
        String email = uniqueEmail("kid");
        String parentEmail = uniqueEmail("kid-parent");
        List<String> emails = emailsDuring(() ->
                signup(email, birthMonthYearsAgo(12), parentEmail.toUpperCase())
                        .expectStatus().isCreated());
        String token = consentToken(emails, parentEmail);

        User u = users.findByEmail(email).orElseThrow();
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        assertThat(u.getParentEmail()).isEqualTo(parentEmail);
        assertThat(u.getParentalConsentTokenHash()).isEqualTo(sha256Hex(token));
        assertThat(u.getParentalConsentRequestedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(u.getParentalConsentAt()).isNull();
        // The child's verification link can't be opened without a full session; it waits for consent.
        assertThat(emails).noneMatch(line -> line.contains("Verification URL for"));
        assertThat(auditFor(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_REQUESTED, u.getId())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("parentEmail", parentEmail));
    }

    // ===== The restricted session ====================================

    @Test
    void pendingSession_givenChild_reachesMeButNotTheModelApiKeysBillingOrSettings() {
        Child kid = signupChild("restricted");

        MeResponse me = me(kid.bearer());
        assertThat(me.ageStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        assertThat(me.parentEmailMasked()).isEqualTo(kid.parentEmail().charAt(0) + "***@godance.test");

        http.post().uri("/v1/chat/completions").header("Authorization", kid.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o-mini",
                        "messages", List.of(Map.of("role", "user", "content", "hi"))))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.detail").value(d ->
                        assertThat((String) d).contains("waiting for a parent's approval"));
        http.post().uri("/v1/images/generations").header("Authorization", kid.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "dall-e-3", "prompt", "a cat"))
                .exchange().expectStatus().isForbidden();
        http.get().uri("/v1/keys").header("Authorization", kid.bearer())
                .exchange().expectStatus().isForbidden();
        http.post().uri("/v1/keys").header("Authorization", kid.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "sneaky"))
                .exchange().expectStatus().isForbidden();
        http.post().uri("/v1/billing/topup").header("Authorization", kid.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 10_00L))
                .exchange().expectStatus().isForbidden();
        http.put().uri("/v1/billing/auto-recharge").header("Authorization", kid.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange().expectStatus().isForbidden();
        http.get().uri("/v1/webhooks/secret").header("Authorization", kid.bearer())
                .exchange().expectStatus().isForbidden();
        http.post().uri("/v1/auth/email").header("Authorization", kid.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", uniqueEmail("elsewhere")))
                .exchange().expectStatus().isForbidden();
        http.get().uri("/v1/usage").header("Authorization", kid.bearer())
                .exchange().expectStatus().isForbidden();
        assertThat(stripeMock.getAllServeEvents()).isEmpty();

        // Signing in again doesn't lift it.
        String again = login(kid.email());
        assertThat(me(again).ageStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        http.get().uri("/v1/keys").header("Authorization", again).exchange().expectStatus().isForbidden();
    }

    // ===== The parent's consent page =================================

    @Test
    void consentView_givenEmailedToken_expectsChildFirstNameMaskedEmailNoticeAndBounds() {
        Child kid = signupChild("view");

        ParentalConsentView view = consentView(kid.token())
                .expectStatus().isOk()
                .expectBody(ParentalConsentView.class).returnResult().getResponseBody();

        assertThat(view.childFirstName()).isEqualTo("Kim");
        assertThat(view.childEmailMasked()).isEqualTo("v***@godance.test");
        assertThat(view.minTopupCents()).isEqualTo(1_00L);
        assertThat(view.maxTopupCents()).isEqualTo(1_000_00L);
        assertThat(view.currency()).isEqualTo("usd");
        assertThat(view.expiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(7)),
                within(1, ChronoUnit.MINUTES));
        assertThat(view.notice().collected()).anyMatch(item -> item.contains("90 days"));
        assertThat(view.notice().sharing()).contains("third-party AI model providers");
        assertThat(view.notice().noSale()).contains("does not sell");
        assertThat(view.notice().parentRights()).contains("hello@godance.ai");
    }

    @Test
    void consentView_givenUnknownToken_returns404() {
        consentView("not-a-real-token").expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void consentView_givenTokenOlderThanSevenDays_returns410() {
        Child kid = signupChild("expired");
        User u = reload(kid.id());
        u.setParentalConsentRequestedAt(Instant.now().minus(Duration.ofDays(7)).minusSeconds(60));
        users.save(u);

        consentView(kid.token()).expectStatus().isEqualTo(HttpStatus.GONE);
        http.post().uri("/v1/auth/parental-consent/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", kid.token(), "amountCents", 10_00L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GONE);
        assertThat(stripeMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void checkout_givenEmailedToken_createsSessionCreditingTheChildPaidByTheParent() {
        Child kid = signupChild("checkout");
        stripeMock.stubFor(post(urlEqualTo("/v1/checkout/sessions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"cs_consent_1","object":"checkout.session","mode":"payment",
                                 "url":"https://checkout.stripe.com/c/pay/cs_consent_1","livemode":false}
                                """)));

        TopUpResponse out = http.post().uri("/v1/auth/parental-consent/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", kid.token(), "amountCents", 10_00L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TopUpResponse.class).returnResult().getResponseBody();

        assertThat(out.sessionId()).isEqualTo("cs_consent_1");
        assertThat(out.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_consent_1");
        assertThat(out.amountCents()).isEqualTo(10_00L);

        Request create = stripeMock.getAllServeEvents().stream()
                .map(e -> e.getRequest())
                .filter(r -> r.getUrl().equals("/v1/checkout/sessions"))
                .findFirst().orElseThrow();
        String form = URLDecoder.decode(create.getBodyAsString(), StandardCharsets.UTF_8);
        assertThat(form)
                .contains("customer_email=" + kid.parentEmail())
                .contains("client_reference_id=" + kid.id())
                .contains("metadata[godance_user_id]=" + kid.id())
                .contains("metadata[godance_purpose]=parental_consent")
                .contains("payment_method_types[0]=card")
                .contains("line_items[0][price_data][unit_amount]=1000")
                .contains("success_url=http://localhost:5173/parent-consent?status=approved")
                .doesNotContain("customer=")
                .doesNotContain("setup_future_usage")
                .doesNotContain("Kim");   // nothing about the child beyond the opaque id
        // No Stripe customer is created for the child, and the token still works (a retry after cancel).
        assertThat(stripeCalled("/v1/customers")).isFalse();
        consentView(kid.token()).expectStatus().isOk();
        assertThat(auditFor(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_CHECKOUT, kid.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("sessionId", "cs_consent_1"));
    }

    @Test
    void checkout_givenAmountOutsideTopUpBounds_returns400() {
        Child kid = signupChild("checkout-bounds");

        for (long cents : new long[] {50L, 1_000_01L}) {
            http.post().uri("/v1/auth/parental-consent/checkout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("token", kid.token(), "amountCents", cents))
                    .exchange()
                    .expectStatus().isBadRequest();
        }
        assertThat(stripeMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void checkout_givenUnknownToken_returns404() {
        http.post().uri("/v1/auth/parental-consent/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", "not-a-real-token", "amountCents", 10_00L))
                .exchange()
                .expectStatus().isNotFound();
        assertThat(stripeMock.getAllServeEvents()).isEmpty();
    }

    // ===== The paid webhook grants consent ===========================

    @Test
    void webhook_givenPaidConsentSession_grantsConsentCreditsChildAndEndsTheRestrictedSession() {
        Child kid = signupChild("granted");
        int sessionVersionBefore = reload(kid.id()).getSessionVersion();
        String paid = checkoutEvent(EVT_COMPLETED, "evt_consent_paid", "cs_consent_paid", kid.id(),
                15_00L, "paid", true);

        List<String> emails = emailsDuring(() -> deliver(paid));

        User u = reload(kid.id());
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.CONSENT_GRANTED);
        assertThat(u.getParentalConsentAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(u.getParentalConsentTokenHash()).isNull();
        assertThat(u.getCreditBalanceCents()).isEqualTo(15_00L);
        assertThat(u.getSessionVersion()).isEqualTo(sessionVersionBefore + 1);
        assertThat(u.getStripePaymentMethodId()).isNull();
        // The parent's card is never looked up to be saved on the child's account.
        assertThat(stripeCalled("/v1/checkout/sessions/cs_consent_paid")).isFalse();
        assertThat(emails)
                .anyMatch(line -> line.contains("Parental consent granted for " + kid.email()))
                .anyMatch(line -> line.contains("Verification URL for " + kid.email()));
        assertThat(auditFor(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_GRANTED, kid.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail())
                        .containsEntry("sessionId", "cs_consent_paid")
                        .containsEntry("eventId", "evt_consent_paid"));

        // The restricted token stops working, and the link is used up.
        http.get().uri("/v1/auth/me").header("Authorization", kid.bearer())
                .exchange().expectStatus().isUnauthorized();
        consentView(kid.token()).expectStatus().isNotFound();

        // Signing in again gives full access.
        String full = login(kid.email());
        assertThat(me(full).ageStatus()).isEqualTo(AgeStatus.CONSENT_GRANTED);
        assertThat(me(full).creditBalanceCents()).isEqualTo(15_00L);
        http.get().uri("/v1/keys").header("Authorization", full).exchange().expectStatus().isOk();
        http.get().uri("/v1/usage").header("Authorization", full).exchange().expectStatus().isOk();

        // A redelivery neither credits nor grants again.
        deliver(paid);
        assertThat(reload(kid.id()).getCreditBalanceCents()).isEqualTo(15_00L);
        assertThat(reload(kid.id()).getSessionVersion()).isEqualTo(sessionVersionBefore + 1);
        assertThat(auditFor(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_GRANTED, kid.id())).hasSize(1);
    }

    @Test
    void webhook_givenConsentSessionCompletedUnpaid_doesNotGrant() {
        Child kid = signupChild("unpaid");

        deliver(checkoutEvent(EVT_COMPLETED, "evt_consent_unpaid", "cs_consent_unpaid", kid.id(),
                15_00L, "unpaid", true));

        User u = reload(kid.id());
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        assertThat(u.getCreditBalanceCents()).isZero();
        assertThat(u.getParentalConsentAt()).isNull();
        assertThat(me(kid.bearer()).ageStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        consentView(kid.token()).expectStatus().isOk();
    }

    @Test
    void webhook_givenConsentSessionAsyncPaymentFailed_doesNotGrant() {
        Child kid = signupChild("async-failed");

        deliver(checkoutEvent(EVT_COMPLETED, "evt_consent_af_completed", "cs_consent_af", kid.id(),
                15_00L, "unpaid", true));
        deliver(checkoutEvent(EVT_ASYNC_FAILED, "evt_consent_af_failed", "cs_consent_af", kid.id(),
                15_00L, "unpaid", true));

        User u = reload(kid.id());
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        assertThat(u.getCreditBalanceCents()).isZero();
        assertThat(auditFor(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_GRANTED, kid.id())).isEmpty();
    }

    @Test
    void webhook_givenConsentSessionAsyncPaymentSucceeded_grants() {
        Child kid = signupChild("async-ok");

        deliver(checkoutEvent(EVT_COMPLETED, "evt_consent_as_completed", "cs_consent_as", kid.id(),
                20_00L, "unpaid", true));
        assertThat(reload(kid.id()).getAgeStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        deliver(checkoutEvent(EVT_ASYNC_SUCCEEDED, "evt_consent_as_ok", "cs_consent_as", kid.id(),
                20_00L, "paid", true));

        User u = reload(kid.id());
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.CONSENT_GRANTED);
        assertThat(u.getCreditBalanceCents()).isEqualTo(20_00L);
    }

    /** Only the consent metadata grants; a plain paid top-up for the same account doesn't. */
    @Test
    void webhook_givenPaidTopUpWithoutConsentMetadata_creditsButDoesNotGrant() {
        Child kid = signupChild("plain-topup");

        deliver(checkoutEvent(EVT_COMPLETED, "evt_plain_paid", "cs_plain_paid", kid.id(),
                5_00L, "paid", false));

        User u = reload(kid.id());
        assertThat(u.getCreditBalanceCents()).isEqualTo(5_00L);
        assertThat(u.getAgeStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
    }

    // ===== Resend ======================================================

    @Test
    void resend_givenCooldownPassed_emailsANewLinkAndTheOldOneStopsWorking() {
        Child kid = signupChild("resend");
        User u = reload(kid.id());
        u.setParentalConsentRequestedAt(Instant.now().minus(Duration.ofMinutes(2)));
        users.save(u);

        List<String> emails = emailsDuring(() -> http.post().uri("/v1/auth/parental-consent/resend")
                .header("Authorization", kid.bearer())
                .exchange()
                .expectStatus().isNoContent());
        String newToken = consentToken(emails, kid.parentEmail());

        assertThat(newToken).isNotEqualTo(kid.token());
        consentView(kid.token()).expectStatus().isNotFound();
        consentView(newToken).expectStatus().isOk();
        assertThat(auditFor(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_REQUESTED, kid.id())).hasSize(2);
    }

    @Test
    void resend_givenEmailJustSent_returns429() {
        Child kid = signupChild("resend-cooldown");

        http.post().uri("/v1/auth/parental-consent/resend")
                .header("Authorization", kid.bearer())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        consentView(kid.token()).expectStatus().isOk();
    }

    @Test
    void resend_givenAdultSession_returns403() {
        String email = uniqueEmail("adult-resend");
        String bearer = "Bearer " + signup(email, birthMonthYearsAgo(30), null)
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody().accessToken();

        http.post().uri("/v1/auth/parental-consent/resend")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void resend_givenNoSession_returns401() {
        http.post().uri("/v1/auth/parental-consent/resend")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
