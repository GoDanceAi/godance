package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AutoRechargeAttempt;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.Conversation;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.OAuthIdentity;
import ai.godance.domain.OAuthProvider;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.AutoRechargeAttemptRepository;
import ai.godance.repository.ConversationMessageRepository;
import ai.godance.repository.ConversationRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.OAuthIdentityRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AccountClosureService;
import ai.godance.service.AuditLogService;
import ai.godance.service.ConversationService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.GenerationJobSettlement;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@code POST /v1/admin/users/{id}/close}: an operator closes an account on
 * its owner's request. The owner is locked out everywhere, their personal
 * data is anonymised or deleted, their media purged; the billing records
 * and the balance stay. Admin-only, and safe to repeat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AccountClosureIntegrationTest {

    private static final String ADMIN = "closure-admin@godance.test";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String BUCKET = "godance-closure-test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> ADMIN);
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
        r.add("godance.object-storage.endpoint",          MINIO::getS3URL);
        r.add("godance.object-storage.access-key",        MINIO::getUserName);
        r.add("godance.object-storage.secret-key",        MINIO::getPassword);
        r.add("godance.object-storage.bucket",            () -> BUCKET);
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    static S3Client inspector;

    @BeforeAll
    static void startInspector() {
        inspector = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterAll
    static void stopInspector() {
        if (inspector != null) inspector.close();
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledgerService;
    @Autowired CreditLedgerRepository ledger;
    @Autowired UsageRecordRepository usage;
    @Autowired AutoRechargeAttemptRepository attempts;
    @Autowired OAuthIdentityRepository identities;
    @Autowired ConversationService conversationService;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobSettlement settlement;
    @Autowired AuditLogEntryRepository audit;
    @Autowired ObjectStorageClient storage;

    private String adminBearer;
    private UUID adminId;

    @BeforeEach
    void signInAdmin() {
        adminBearer = signUpOrLogIn(ADMIN);
        User admin = users.findByEmail(ADMIN).orElseThrow();
        admin.setEmailVerified(true);
        users.save(admin);
        adminId = admin.getId();
    }

    // ===== Closing an account ===========================================

    @Test
    void close_givenAdmin_locksOutAndErasesTheOwnerButKeepsBilling() {
        String email = "closing-" + UUID.randomUUID() + "@godance.test";
        String bearer = signUpOrLogIn(email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();

        // An API key, credit and usage on it, a saved card with auto-recharge, a social login.
        CreateApiKeyResponse key = createApiKey(bearer);
        ledgerService.topUp(userId, 50_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        seedUsage(userId, key.id());
        User owner = users.findById(userId).orElseThrow();
        owner.setStripeCustomerId("cus_closure_" + UUID.randomUUID().toString().substring(0, 8));
        owner.setStripePaymentMethodId("pm_card_visa");
        owner.setAutoRechargeEnabled(true);
        owner.setAutoRechargeThresholdCents(5_00L);
        owner.setAutoRechargeTopupCents(20_00L);
        // A child's account approved by a parent: the birth month and parent's email must go too.
        owner.setBirthMonth(java.time.LocalDate.now().minusYears(12).withDayOfMonth(1));
        owner.setParentEmail("parent-" + UUID.randomUUID() + "@godance.test");
        users.save(owner);
        AutoRechargeAttempt attempt = new AutoRechargeAttempt();
        attempt.setUserId(userId);
        attempt.setAmountCents(20_00L);
        attempt.setStatus(AutoRechargeAttempt.Status.SUCCEEDED);
        attempt = attempts.save(attempt);
        String googleSubject = "google-" + UUID.randomUUID();
        OAuthIdentity identity = new OAuthIdentity();
        identity.setUserId(userId);
        identity.setProvider(OAuthProvider.GOOGLE);
        identity.setSubject(googleSubject);
        identities.save(identity);
        // Conversations (one archived) and a finished image job with its media.
        Conversation chat = conversationService.create(userId, "my private chat");
        conversationService.appendMessage(userId, chat.getId(),
                new ConversationService.AppendInput("user", "something personal", null, null, null, null));
        conversationService.appendMessage(userId, chat.getId(),
                new ConversationService.AppendInput("assistant", "a reply", "gpt-4o", 10, 20, 1L));
        Conversation archived = conversationService.create(userId, "archived chat");
        conversationService.archive(userId, archived.getId());
        GenerationJob job = doneJob(userId);
        storage.upload("images/" + job.getId() + "/0.png", new byte[] {1, 2, 3}, "image/png");

        // Both credentials work before closure.
        http.get().uri("/v1/keys").header("Authorization", bearer).exchange().expectStatus().isOk();
        http.get().uri("/v1/jobs").header("Authorization", "Bearer " + key.plaintext())
                .exchange().expectStatus().isOk();

        long balanceBefore = users.findById(userId).orElseThrow().getCreditBalanceCents();
        long ledgerSumBefore = ledger.sumDeltaForUser(userId);
        long ledgerRowsBefore = ledgerRows(userId);
        int usageRowsBefore = usageRows(userId);
        assertThat(balanceBefore).isEqualTo(50_00L);

        closeAs(adminBearer, userId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId.toString())
                .jsonPath("$.alreadyClosed").isEqualTo(false)
                .jsonPath("$.apiKeysDeleted").isEqualTo(1)
                .jsonPath("$.oauthIdentitiesDeleted").isEqualTo(1)
                .jsonPath("$.conversationsDeleted").isEqualTo(2)
                .jsonPath("$.openJobsEnded").isEqualTo(0)
                .jsonPath("$.jobsPurged").isEqualTo(1)
                .jsonPath("$.jobsNotPurged").isEqualTo(0);

        // Locked out: no sign-in, and the old session and the API key are refused.
        http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isUnauthorized();
        http.get().uri("/v1/keys").header("Authorization", bearer).exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/jobs").header("Authorization", "Bearer " + key.plaintext())
                .exchange().expectStatus().isUnauthorized();

        // Anonymised.
        User closed = users.findById(userId).orElseThrow();
        assertThat(closed.getEmail()).isEqualTo("closed-" + userId + "@closed.invalid");
        assertThat(closed.getFullName()).isEqualTo(AccountClosureService.CLOSED_FULL_NAME);
        assertThat(closed.getPasswordHash()).isNull();
        assertThat(closed.getBirthMonth()).isNull();
        assertThat(closed.getParentEmail()).isNull();
        assertThat(closed.getPendingEmail()).isNull();
        assertThat(closed.getEmailVerificationToken()).isNull();
        assertThat(closed.getPasswordResetTokenHash()).isNull();
        assertThat(closed.getWebhookSigningSecret()).isNull();
        assertThat(closed.getWebhookSigningSecretPrevious()).isNull();
        assertThat(closed.isActive()).isFalse();
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(identities.findByProviderAndSubject(OAuthProvider.GOOGLE, googleSubject)).isEmpty();
        // Never charged again; the Stripe customer stays with the billing records.
        assertThat(closed.isAutoRechargeEnabled()).isFalse();
        assertThat(closed.getStripePaymentMethodId()).isNull();
        assertThat(closed.getStripeCustomerId()).isEqualTo(owner.getStripeCustomerId());

        // Conversations and messages deleted; the job purged with its media.
        assertThat(conversations.findById(chat.getId())).isEmpty();
        assertThat(conversations.findById(archived.getId())).isEmpty();
        assertThat(messages.findByConversationIdOrderByCreatedAt(chat.getId())).isEmpty();
        GenerationJob purged = jobs.findById(job.getId()).orElseThrow();
        assertThat(purged.getPrompt()).isEqualTo(Map.of("purged", true));
        assertThat(purged.getOutputUrl()).isNull();
        assertThat(purged.getWebhookUrl()).isNull();
        assertThat(purged.getPurgedAt()).isNotNull();
        assertThat(purged.getActualCostCents()).isEqualTo(480L);
        assertThat(keysUnder("images/" + job.getId() + "/")).isEmpty();

        // Billing records and the balance are untouched.
        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(balanceBefore);
        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(ledgerSumBefore);
        assertThat(ledgerRows(userId)).isEqualTo(ledgerRowsBefore);
        assertThat(usageRows(userId)).isEqualTo(usageRowsBefore);
        assertThat(attempts.findById(attempt.getId())).isPresent();

        // Audited, naming the operator.
        List<AuditLogEntry> rows = audit.findByEventType(AuditLogService.EVT_ADMIN_ACCOUNT_CLOSED).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventDetail()).containsEntry("actorUserId", adminId.toString());
    }

    @Test
    void close_givenAlreadyClosedAccount_changesNothingMore() {
        String email = "twice-" + UUID.randomUUID() + "@godance.test";
        signUpOrLogIn(email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledgerService.topUp(userId, 10_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        closeAs(adminBearer, userId).expectStatus().isOk();
        User first = users.findById(userId).orElseThrow();

        closeAs(adminBearer, userId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.alreadyClosed").isEqualTo(true)
                .jsonPath("$.apiKeysDeleted").isEqualTo(0)
                .jsonPath("$.conversationsDeleted").isEqualTo(0)
                .jsonPath("$.jobsPurged").isEqualTo(0)
                .jsonPath("$.jobsNotPurged").isEqualTo(0);

        User second = users.findById(userId).orElseThrow();
        assertThat(second.getEmail()).isEqualTo(first.getEmail());
        assertThat(second.isActive()).isFalse();
        assertThat(second.getCreditBalanceCents()).isEqualTo(10_00L);
        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(10_00L);
    }

    @Test
    void close_givenJobStillRunning_endsItRefundedAndPurgesIt() {
        String email = "running-" + UUID.randomUUID() + "@godance.test";
        signUpOrLogIn(email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledgerService.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        GenerationJob open = new GenerationJob();
        open.setUserId(userId);
        open.setModality(Modality.IMAGE);
        open.setProvider(Provider.OPENAI);
        open.setModel("gpt-image-2");
        open.setPrompt(Map.of("prompt", "in flight"));
        open.setEstimatedCostCents(500L);
        open.setRequestId("img_" + UUID.randomUUID());
        open.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(settlement.accept(open, null)).isTrue();
        assertThat(settlement.start(open)).isTrue();
        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L - 500L);

        closeAs(adminBearer, userId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openJobsEnded").isEqualTo(1)
                .jsonPath("$.jobsPurged").isEqualTo(1);

        GenerationJob ended = jobs.findById(open.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(ended.getPrompt()).isEqualTo(Map.of("purged", true));
        assertThat(ended.getPurgedAt()).isNotNull();
        // The estimate comes back, as the sweep would have refunded it.
        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(100_00L);
    }

    // ===== Who may close ================================================

    @Test
    void close_givenNonAdminCaller_returns403AndChangesNothing() {
        String callerEmail = "not-admin-" + UUID.randomUUID() + "@godance.test";
        String callerBearer = signUpOrLogIn(callerEmail);
        String targetEmail = "target-" + UUID.randomUUID() + "@godance.test";
        signUpOrLogIn(targetEmail);
        UUID targetId = users.findByEmail(targetEmail).orElseThrow().getId();

        closeAs(callerBearer, targetId)
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        User target = users.findById(targetId).orElseThrow();
        assertThat(target.getEmail()).isEqualTo(targetEmail);
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void close_givenApiKeyOfAnAdmin_returns403() {
        CreateApiKeyResponse adminKey = createApiKey(adminBearer);
        String targetEmail = "key-target-" + UUID.randomUUID() + "@godance.test";
        signUpOrLogIn(targetEmail);
        UUID targetId = users.findByEmail(targetEmail).orElseThrow().getId();

        closeAs("Bearer " + adminKey.plaintext(), targetId).expectStatus().isForbidden();

        assertThat(users.findById(targetId).orElseThrow().isActive()).isTrue();
    }

    @Test
    void close_givenUnknownUser_returns404() {
        closeAs(adminBearer, UUID.randomUUID()).expectStatus().isNotFound();
    }

    // ===== Helpers ======================================================

    private WebTestClient.ResponseSpec closeAs(String bearer, UUID userId) {
        return http.post().uri("/v1/admin/users/" + userId + "/close")
                .header("Authorization", bearer)
                .exchange();
    }

    private String signUpOrLogIn(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Closure Test"))
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    private CreateApiKeyResponse createApiKey(String bearer) {
        return http.post().uri("/v1/keys")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "laptop"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
    }

    private void seedUsage(UUID userId, UUID apiKeyId) {
        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(apiKeyId);
        r.setModality(Modality.TEXT);
        r.setModelName("gpt-4o-mini");
        r.setProvider(Provider.OPENAI);
        r.setInputTokens(100);
        r.setOutputTokens(50);
        r.setCostCents(3L);
        r.setProviderCostCents(1L);
        r.setRegion(Region.LOCAL);
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setRequestId("req-" + UUID.randomUUID());
        r.setOutcome(CallOutcome.OK);
        r.setRecordedAt(Instant.now());
        usage.save(r);
    }

    private GenerationJob doneJob(UUID userId) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "a portrait of me"));
        job.setStatus(JobStatus.DONE);
        job.setOutputUrl("https://storage.invalid/output");
        job.setWebhookUrl("https://hooks.invalid/me");
        job.setEstimatedCostCents(500L);
        job.setActualCostCents(480L);
        job.setRequestId("img_" + UUID.randomUUID());
        return jobs.save(job);
    }

    private long ledgerRows(UUID userId) {
        return ledger.findByUserIdOrderByOccurredAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
    }

    private int usageRows(UUID userId) {
        return usage.findByUserIdAndRecordedAtGreaterThanEqual(userId, Instant.EPOCH).size();
    }

    private static List<String> keysUnder(String prefix) {
        return inspector.listObjectsV2Paginator(b -> b.bucket(BUCKET).prefix(prefix)).contents().stream()
                .map(S3Object::key)
                .toList();
    }
}
