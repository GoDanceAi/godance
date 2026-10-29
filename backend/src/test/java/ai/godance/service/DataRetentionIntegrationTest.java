package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.Conversation;
import ai.godance.domain.ConversationMessage;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.ConversationMessageRepository;
import ai.godance.repository.ConversationRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.MinioTestImage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The Privacy Policy's retention schedule as {@link DataRetentionJob} runs it:
 * media and prompts of ended jobs go after 90 days, archived conversations
 * 30 days after archiving, audit rows after a year. Each test seeds rows on
 * either side of its cutoff and runs one full pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class DataRetentionIntegrationTest {

    private static final String BUCKET = "godance-retention-test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterAll
    static void stopInspector() {
        if (inspector != null) inspector.close();
    }

    /** The real MinIO-backed client, spied so one test can make a delete fail. */
    @MockitoSpyBean ObjectStorageClient storage;

    @Autowired DataRetentionService retention;
    @Autowired UserRepository users;
    @Autowired GenerationJobRepository jobs;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired AuditLogEntryRepository audit;

    // ===== Generated media and prompts: 90 days =========================

    @Test
    void run_givenEndedJobOlderThan90Days_deletesItsMediaAndRedactsIt() {
        UUID owner = newUser();
        GenerationJob oldImage = job(owner, Modality.IMAGE, JobStatus.DONE, daysAgo(91));
        putObjects("images/" + oldImage.getId() + "/", "0.png", "1.png");
        GenerationJob oldVideo = job(owner, Modality.VIDEO, JobStatus.FAILED, daysAgo(91));
        putObjects("videos/" + oldVideo.getId() + "/", "0.mp4");
        GenerationJob recent = job(owner, Modality.IMAGE, JobStatus.DONE, daysAgo(89));
        putObjects("images/" + recent.getId() + "/", "0.png");
        GenerationJob stillRunning = job(owner, Modality.IMAGE, JobStatus.RUNNING, daysAgo(91));
        putObjects("images/" + stillRunning.getId() + "/", "0.png");

        DataRetentionJob.Summary first = DataRetentionJob.run(retention, Instant.now());

        assertThat(first.jobsPurged()).isEqualTo(2);
        assertThat(first.jobsFailed()).isZero();
        assertPurged(oldImage);
        assertThat(keysUnder("images/" + oldImage.getId() + "/")).isEmpty();
        assertPurged(oldVideo);
        assertThat(keysUnder("videos/" + oldVideo.getId() + "/")).isEmpty();
        assertUntouched(recent);
        assertThat(keysUnder("images/" + recent.getId() + "/")).hasSize(1);
        assertUntouched(stillRunning);
        assertThat(keysUnder("images/" + stillRunning.getId() + "/")).hasSize(1);

        DataRetentionJob.Summary second = DataRetentionJob.run(retention, Instant.now());

        assertThat(second.jobsPurged()).isZero();
        assertThat(second.jobsFailed()).isZero();
        assertPurged(oldImage);
        assertUntouched(recent);
        assertUntouched(stillRunning);
    }

    @Test
    void run_givenStorageFailsForAJob_leavesItUnpurgedAndPurgesItOnTheNextRun() {
        UUID owner = newUser();
        GenerationJob job = job(owner, Modality.IMAGE, JobStatus.DONE, daysAgo(120));
        String prefix = "images/" + job.getId() + "/";
        putObjects(prefix, "0.png");
        doThrow(new IllegalStateException("object storage unavailable")).when(storage).deletePrefix(prefix);

        DataRetentionJob.Summary failed = DataRetentionJob.run(retention, Instant.now());

        assertThat(failed.jobsFailed()).isEqualTo(1);
        assertUntouched(job);
        assertThat(keysUnder(prefix)).hasSize(1);

        reset(storage);   // storage is back
        DataRetentionJob.Summary retried = DataRetentionJob.run(retention, Instant.now());

        assertThat(retried.jobsPurged()).isEqualTo(1);
        assertPurged(job);
        assertThat(keysUnder(prefix)).isEmpty();
    }

    // ===== Archived conversations: 30 days after archiving ==============

    @Test
    void run_givenArchivedConversations_deletesThoseArchivedOver30DaysAgoWithTheirMessages() {
        UUID owner = newUser();
        Conversation expired = conversation(owner, daysAgo(31), "first", "second");
        Conversation recentlyArchived = conversation(owner, daysAgo(29), "kept");
        Conversation active = conversation(owner, null, "still in use");

        DataRetentionJob.Summary summary = DataRetentionJob.run(retention, Instant.now());

        assertThat(summary.conversationsDeleted()).isEqualTo(1);
        assertThat(conversations.findById(expired.getId())).isEmpty();
        assertThat(messages.findByConversationIdOrderByCreatedAt(expired.getId())).isEmpty();
        assertThat(conversations.findById(recentlyArchived.getId())).isPresent();
        assertThat(messages.findByConversationIdOrderByCreatedAt(recentlyArchived.getId())).hasSize(1);
        assertThat(conversations.findById(active.getId())).isPresent();
        assertThat(messages.findByConversationIdOrderByCreatedAt(active.getId())).hasSize(1);
    }

    // ===== Audit log: 365 days ==========================================

    @Test
    void run_givenAuditRows_deletesThoseOlderThanAYear() {
        AuditLogEntry expired = auditRow(daysAgo(366));
        AuditLogEntry kept = auditRow(daysAgo(364));

        DataRetentionJob.Summary summary = DataRetentionJob.run(retention, Instant.now());

        assertThat(summary.auditRowsDeleted()).isEqualTo(1);
        assertThat(audit.findById(expired.getId())).isEmpty();
        assertThat(audit.findById(kept.getId())).isPresent();
    }

    @Test
    void run_givenChildAccountsAwaitingConsent_deletesThoseAskedOver30DaysAgoWithTheParentsEmail() {
        UUID stale = newChild(AgeStatus.CONSENT_PENDING, daysAgo(31));
        UUID recent = newChild(AgeStatus.CONSENT_PENDING, daysAgo(29));
        UUID approved = newChild(AgeStatus.CONSENT_GRANTED, daysAgo(40));
        AuditLogEntry staleRequest = auditRowFor(stale);
        AuditLogEntry approvedRequest = auditRowFor(approved);

        DataRetentionJob.Summary summary = DataRetentionJob.run(retention, Instant.now());

        assertThat(summary.unapprovedChildAccountsDeleted()).isEqualTo(1);
        assertThat(users.findById(stale)).isEmpty();
        assertThat(audit.findById(staleRequest.getId())).isEmpty();   // it held the parent's email
        assertThat(users.findById(recent)).isPresent();
        assertThat(users.findById(approved)).isPresent();
        assertThat(audit.findById(approvedRequest.getId())).isPresent();
    }

    // ===== Helpers ======================================================

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private UUID newUser() {
        User u = new User();
        u.setEmail("retention-" + UUID.randomUUID() + "@godance.test");
        u.setFullName("Retention Tester");
        u.setRole(UserRole.USER);
        return users.save(u).getId();
    }

    private GenerationJob job(UUID userId, Modality modality, JobStatus status, Instant createdAt) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(modality);
        job.setProvider(Provider.OPENAI);
        job.setModel(modality == Modality.VIDEO ? "sora-2" : "gpt-image-2");
        job.setPrompt(Map.of("prompt", "a private prompt", "n", 1));
        job.setStatus(status);
        job.setOutputUrl("https://storage.invalid/output");
        job.setWebhookUrl("https://hooks.invalid/godance");
        job.setEstimatedCostCents(500L);
        job.setActualCostCents(480L);
        job.setRequestId("img_" + UUID.randomUUID());
        job.setCreatedAt(createdAt);
        return jobs.save(job);
    }

    private void assertPurged(GenerationJob original) {
        GenerationJob j = jobs.findById(original.getId()).orElseThrow();
        assertThat(j.getPrompt()).isEqualTo(Map.of("purged", true));
        assertThat(j.getOutputUrl()).isNull();
        assertThat(j.getWebhookUrl()).isNull();
        assertThat(j.getPurgedAt()).isNotNull();
        // Billing fields stay.
        assertThat(j.getStatus()).isEqualTo(original.getStatus());
        assertThat(j.getEstimatedCostCents()).isEqualTo(500L);
        assertThat(j.getActualCostCents()).isEqualTo(480L);
        assertThat(j.getRequestId()).isEqualTo(original.getRequestId());
    }

    private void assertUntouched(GenerationJob original) {
        GenerationJob j = jobs.findById(original.getId()).orElseThrow();
        assertThat(j.getPrompt()).containsEntry("prompt", "a private prompt");
        assertThat(j.getOutputUrl()).isEqualTo("https://storage.invalid/output");
        assertThat(j.getWebhookUrl()).isEqualTo("https://hooks.invalid/godance");
        assertThat(j.getPurgedAt()).isNull();
    }

    private void putObjects(String prefix, String... names) {
        for (String name : names) {
            storage.upload(prefix + name, new byte[] {1, 2, 3}, "application/octet-stream");
        }
    }

    private static List<String> keysUnder(String prefix) {
        return inspector.listObjectsV2Paginator(b -> b.bucket(BUCKET).prefix(prefix)).contents().stream()
                .map(S3Object::key)
                .toList();
    }

    private Conversation conversation(UUID userId, Instant archivedAt, String... contents) {
        Conversation c = new Conversation();
        c.setUserId(userId);
        c.setTitle("retention test");
        c.setArchivedAt(archivedAt);
        c = conversations.save(c);
        for (String content : contents) {
            ConversationMessage m = new ConversationMessage();
            m.setConversationId(c.getId());
            m.setRole("user");
            m.setContent(content);
            messages.save(m);
        }
        return c;
    }

    private AuditLogEntry auditRow(Instant createdAt) {
        AuditLogEntry e = new AuditLogEntry();
        e.setEventType("test.retention");
        e.setOutcome(AuditOutcome.SUCCESS);
        e.setCreatedAt(createdAt);
        return audit.save(e);
    }

    private UUID newChild(AgeStatus status, Instant consentRequestedAt) {
        User u = new User();
        u.setEmail("child-" + UUID.randomUUID() + "@godance.test");
        u.setFullName("Child Tester");
        u.setRole(UserRole.USER);
        u.setAgeStatus(status);
        u.setBirthMonth(java.time.LocalDate.now().minusYears(10).withDayOfMonth(1));
        u.setParentEmail("parent-" + UUID.randomUUID() + "@godance.test");
        u.setParentalConsentRequestedAt(consentRequestedAt);
        return users.save(u).getId();
    }

    private AuditLogEntry auditRowFor(UUID userId) {
        AuditLogEntry e = new AuditLogEntry();
        e.setEventType("auth.parental_consent.requested");
        e.setOutcome(AuditOutcome.SUCCESS);
        e.setUserId(userId);
        e.setCreatedAt(Instant.now());
        return audit.save(e);
    }
}
