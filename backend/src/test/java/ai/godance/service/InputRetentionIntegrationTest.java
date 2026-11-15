package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.InputFile;
import ai.godance.domain.InputRole;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.InputService.InputUse;
import ai.godance.service.InputService.ResolvedInput;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.InputFixtures;
import ai.godance.web.MinioTestImage;
import ai.godance.web.dto.ImageDtos.JobView;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.InputDtos.JobInputView;

/**
 * Pointing at an input, and how long a file lives (round 11 brief 01):
 * {@link InputService#resolve} and the recording of a job's inputs with the
 * job, what a job then reads back, the nightly deletion of files nothing
 * uses, the release of a purged job's files, and closing an account.
 *
 * <p>Inputs are switched on here with people not allowed; the stand-in
 * check clears every image.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.inputs.enabled=true",
        "godance.inputs.allow-people=false"})
@Testcontainers
class InputRetentionIntegrationTest {

    private static final Duration UNUSED_TTL = Duration.ofHours(24);

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
        r.add("godance.object-storage.bucket",            () -> "godance-input-retention-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @TestConfiguration
    static class StandInCheck {
        @Bean
        @Primary   // in place of the built-in check, which asks a model
        InputPersonCheck inputPersonCheck() {
            return (userId, image, contentType) -> InputPersonCheck.Verdict.NO_PERSON;
        }
    }

    /** The real MinIO-backed client, spied so one test can make a delete fail. */
    @MockitoSpyBean ObjectStorageClient storage;

    @Autowired InputService inputs;
    @Autowired GenerationJobSettlement settlement;
    @Autowired DataRetentionService retention;
    @Autowired AccountClosureService closure;
    @Autowired JobReadService jobReads;
    @Autowired JobSaveService saves;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired UserRepository users;
    @Autowired InputFileRepository files;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void stopFailing() {
        reset(storage);
    }

    // ===== Pointing at an input ========================================

    @Test
    void resolve_givenTheCallersFile_expectsTheImageAndNothingWritten() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "gps.jpg");
        long ledgerBefore = ledgerCount(owner);

        List<ResolvedInput> resolved = inputs.resolve(owner, List.of(new InputUse(InputRole.IMAGE, InputRef.file(file.getId()))));

        assertThat(resolved).singleElement().satisfies(input -> {
            assertThat(input.role()).isEqualTo(InputRole.IMAGE);
            assertThat(input.fileId()).isEqualTo(file.getId());
            assertThat(input.sourceJobId()).isNull();
            assertThat(input.contentType()).isEqualTo("image/jpeg");
            assertThat(input.width()).isEqualTo(48);
            assertThat(input.height()).isEqualTo(64);
            assertThat(input.bytes()).isEqualTo(ImageMetadata.strip(InputFixtures.image("gps.jpg")).bytes());
            assertThat(input.ref()).isEqualTo(InputRef.file(file.getId()));
            // A link a provider can fetch, signed and short-lived.
            assertThat(InputFixtures.fetch(inputs.providerUrl(input)).body()).isEqualTo(input.bytes());
            assertThat(inputs.providerUrl(input)).contains("X-Amz-Expires=3600");
        });
        // Looking is not using: no job, no record of a use, the expiry as it was, nothing charged.
        assertThat(inputRowCount(file.getId())).isZero();
        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt()).isEqualTo(file.getExpiresAt());
        assertThat(ledgerCount(owner)).isEqualTo(ledgerBefore);
    }

    @Test
    void resolve_givenAnotherUsersFile_expects404FileNotFoundAndNoLedgerRow() {
        UUID owner = newUser(0);
        UUID other = newUser(50_00);
        InputFile file = upload(owner, "plain.png");
        long ledgerBefore = ledgerCount(other);

        assertRefused(() -> inputs.resolve(other, List.of(new InputUse(InputRole.IMAGE, InputRef.file(file.getId())))),
                404, InputRefusedException.CODE_FILE_NOT_FOUND);
        // The same answer as for a file nobody has.
        assertRefused(() -> inputs.resolve(other, List.of(new InputUse(InputRole.IMAGE, InputRef.file(UUID.randomUUID())))),
                404, InputRefusedException.CODE_FILE_NOT_FOUND);
        assertRefused(() -> inputs.content(other, file.getId()), 404, InputRefusedException.CODE_FILE_NOT_FOUND);

        assertThat(ledgerCount(other)).isEqualTo(ledgerBefore);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(other)).isEmpty();
        assertThat(inputRowCount(file.getId())).isZero();
        // Its owner reads the same bytes back.
        assertThat(inputs.content(owner, file.getId()).bytes()).isEqualTo(InputFixtures.image("plain.png"));
    }

    @Test
    void resolve_givenOneOfTheCallersJobOutputs_expectsTheImageWithItsSize() {
        UUID owner = newUser(0);
        GenerationJob source = doneImageJob(owner, InputFixtures.image("plain.png"), InputFixtures.image("progressive.jpg"));

        List<ResolvedInput> resolved = inputs.resolve(owner, List.of(
                new InputUse(InputRole.FIRST_FRAME, InputRef.jobOutput(source.getId(), 1))));

        assertThat(resolved).singleElement().satisfies(input -> {
            assertThat(input.fileId()).isNull();
            assertThat(input.sourceJobId()).isEqualTo(source.getId());
            assertThat(input.sourceIndex()).isEqualTo(1);
            assertThat(input.contentType()).isEqualTo("image/jpeg");
            assertThat(input.width()).isEqualTo(96);
            assertThat(input.height()).isEqualTo(64);
            assertThat(input.bytes()).isEqualTo(InputFixtures.image("progressive.jpg"));
        });
    }

    @Test
    void resolve_givenAJobOutputThatIsNotTheCallersToUse_expects404JobOutputNotFound() {
        UUID owner = newUser(0);
        UUID other = newUser(0);
        GenerationJob theirs = doneImageJob(other, InputFixtures.image("plain.png"));
        GenerationJob unfinished = doneImageJob(owner, InputFixtures.image("plain.png"));
        jdbc.update("UPDATE generation_jobs SET status = 'RUNNING' WHERE id = ?", unfinished.getId());
        GenerationJob purged = doneImageJob(owner, InputFixtures.image("plain.png"));
        assertThat(retention.purgeJob(purged.getId(), Instant.now())).isTrue();
        GenerationJob mine = doneImageJob(owner, InputFixtures.image("plain.png"));
        GenerationJob video = job(owner, Modality.VIDEO, JobStatus.DONE, Instant.now());
        storage.upload("videos/" + video.getId() + "/0.mp4", new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'}, "video/mp4");
        outputRows.save(new GenerationJobOutput(video.getId(), 0, "videos/" + video.getId() + "/0.mp4", "video/mp4", 8L, null));

        for (InputRef ref : List.of(
                InputRef.jobOutput(theirs.getId(), 0),          // another user's job
                InputRef.jobOutput(UUID.randomUUID(), 0),       // no such job
                InputRef.jobOutput(unfinished.getId(), 0),      // not DONE
                InputRef.jobOutput(purged.getId(), 0),          // its files are gone
                InputRef.jobOutput(mine.getId(), 1),            // no such output
                InputRef.jobOutput(video.getId(), 0))) {        // not an image
            assertRefused(() -> inputs.resolve(owner, List.of(new InputUse(InputRole.FIRST_FRAME, ref))),
                    404, InputRefusedException.CODE_JOB_OUTPUT_NOT_FOUND);
        }
        assertThat(inputs.resolve(owner, List.of(new InputUse(InputRole.FIRST_FRAME, InputRef.jobOutput(mine.getId(), 0)))))
                .hasSize(1);
    }

    @Test
    void resolve_givenAReferenceThatIsNotExactlyOneOfTheTwoForms_expects400() {
        UUID owner = newUser(0);
        InputFile file = upload(owner, "plain.png");
        GenerationJob source = doneImageJob(owner, InputFixtures.image("plain.png"));
        InputRef both = new InputRef(file.getId(), InputRef.jobOutput(source.getId(), 0).jobOutput());

        for (InputRef ref : new InputRef[] {both, new InputRef(null, null), null,
                InputRef.jobOutput(null, 0), InputRef.jobOutput(source.getId(), -1)}) {
            assertRefused(() -> inputs.resolve(owner, List.of(new InputUse(InputRole.IMAGE, ref))),
                    400, InputRefusedException.CODE_INVALID_REFERENCE);
        }
    }

    @Test
    void resolve_givenAnAccountThatMayNotUseInputs_expectsTheSameRefusalsAsAnUpload() {
        UUID child = newUser(0);
        InputFile file = upload(child, "plain.png");
        GenerationJob source = doneImageJob(child, InputFixtures.image("plain.png"));
        List<InputUse> either = List.of(new InputUse(InputRole.IMAGE, InputRef.file(file.getId())));
        List<InputUse> ownOutput = List.of(new InputUse(InputRole.FIRST_FRAME, InputRef.jobOutput(source.getId(), 0)));

        jdbc.update("UPDATE users SET inputs_terms_version = NULL WHERE id = ?", child);
        assertRefused(() -> inputs.resolve(child, either), 403, InputRefusedException.CODE_TERMS_REQUIRED);
        // One of the caller's own outputs is an input too: the same rules.
        assertRefused(() -> inputs.resolve(child, ownOutput), 403, InputRefusedException.CODE_TERMS_REQUIRED);

        jdbc.update("UPDATE users SET inputs_terms_version = 1, age_status = 'CONSENT_GRANTED' WHERE id = ?", child);
        assertRefused(() -> inputs.resolve(child, either), 403, InputRefusedException.CODE_NOT_ALLOWED_FOR_ACCOUNT);
        assertRefused(() -> inputs.resolve(child, ownOutput), 403, InputRefusedException.CODE_NOT_ALLOWED_FOR_ACCOUNT);
    }

    @Test
    void resolve_givenAFileThePersonCheckNeverCleared_expects422PersonNotAllowed() {
        UUID owner = newUser(0);
        // As left by an upload made while people were allowed: nothing checked it.
        byte[] image = InputFixtures.image("plain.png");
        UUID id = UUID.randomUUID();
        String key = "inputs/" + id + "/unchecked.png";
        storage.upload(key, image, "image/png");
        files.save(new InputFile(id, owner, key, "image/png", image.length, 64, 48, null,
                Instant.now(), Instant.now().plus(UNUSED_TTL)));

        assertRefused(() -> inputs.resolve(owner, List.of(new InputUse(InputRole.IMAGE, InputRef.file(id)))),
                422, InputRefusedException.CODE_PERSON_NOT_ALLOWED);
        assertRefused(() -> inputs.content(owner, id), 422, InputRefusedException.CODE_PERSON_NOT_ALLOWED);
    }

    // ===== Recording a job's inputs ====================================

    @Test
    void accept_givenInputs_recordsThemInOrderStopsTheFilesExpiryAndTheJobReadsThemBack() {
        UUID owner = newUser(50_00);
        InputFile image = upload(owner, "gps.png");
        InputFile mask = upload(owner, "plain.png");
        GenerationJob source = doneImageJob(owner, InputFixtures.image("plain.jpg"));
        List<ResolvedInput> resolved = inputs.resolve(owner, List.of(
                new InputUse(InputRole.IMAGE, InputRef.file(image.getId())),
                new InputUse(InputRole.IMAGE, InputRef.jobOutput(source.getId(), 0)),
                new InputUse(InputRole.MASK, InputRef.file(mask.getId()))));
        GenerationJob job = newJob(owner, 12L);

        assertThat(settlement.accept(job, null, resolved)).isTrue();

        // In use: no expiry any more.
        assertThat(files.findById(image.getId()).orElseThrow().getExpiresAt()).isNull();
        assertThat(files.findById(mask.getId()).orElseThrow().getExpiresAt()).isNull();
        assertThat(ledger.authoritativeBalance(owner)).isEqualTo(50_00 - 12);

        JobView view = jobReads.get(owner, job.getId());
        assertThat(view.inputs()).hasSize(3);
        JobInputView first = view.inputs().get(0);
        assertThat(first.role()).isEqualTo("image");
        assertThat(first.fileId()).isEqualTo(image.getId());
        assertThat(first.jobOutput()).isNull();
        assertThat(InputFixtures.fetch(first.url()).body()).isEqualTo(storage.download(image.getStorageKey()));
        assertThat(first.url()).contains("X-Amz-Expires=300");
        JobInputView second = view.inputs().get(1);
        assertThat(second.role()).isEqualTo("image");
        assertThat(second.fileId()).isNull();
        assertThat(second.jobOutput().jobId()).isEqualTo(source.getId());
        assertThat(second.jobOutput().index()).isZero();
        assertThat(InputFixtures.fetch(second.url()).body()).isEqualTo(InputFixtures.image("plain.jpg"));
        JobInputView third = view.inputs().get(2);
        assertThat(third.role()).isEqualTo("mask");
        assertThat(third.fileId()).isEqualTo(mask.getId());
        // The list reads the same, and a job without inputs reads an empty list.
        List<JobView> listed = jobReads.list(owner, null, null, null, null, null, null);
        assertThat(listed.stream().filter(v -> v.id().equals(job.getId())).findFirst().orElseThrow().inputs())
                .extracting(JobInputView::role).containsExactly("image", "image", "mask");
        assertThat(listed.stream().filter(v -> v.id().equals(source.getId())).findFirst().orElseThrow().inputs())
                .isEmpty();
    }

    @Test
    void accept_givenAFileDeletedSinceItWasResolved_expectsFileNotFoundAndNoJobAndNoLedgerRow() {
        UUID owner = newUser(50_00);
        InputFile kept = upload(owner, "plain.png");
        InputFile gone = upload(owner, "plain.jpg");
        List<ResolvedInput> resolved = inputs.resolve(owner, List.of(
                new InputUse(InputRole.IMAGE, InputRef.file(kept.getId())),
                new InputUse(InputRole.IMAGE, InputRef.file(gone.getId()))));
        inputs.delete(owner, gone.getId(), null);
        long ledgerBefore = ledgerCount(owner);
        GenerationJob job = newJob(owner, 12L);

        assertRefused(() -> settlement.accept(job, null, resolved), 404, InputRefusedException.CODE_FILE_NOT_FOUND);

        // The whole acceptance is undone: the pre-debit, the job, and the file that was still there.
        assertThat(ledgerCount(owner)).isEqualTo(ledgerBefore);
        assertThat(ledger.authoritativeBalance(owner)).isEqualTo(50_00);
        assertThat(users.findById(owner).orElseThrow().getCreditBalanceCents()).isEqualTo(50_00);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(owner)).isEmpty();
        assertThat(inputRowCount(kept.getId())).isZero();
        assertThat(files.findById(kept.getId()).orElseThrow().getExpiresAt()).isNotNull();
    }

    @Test
    void accept_givenTooLittleCredit_expectsFalseAndTheFileStillUnused() {
        UUID owner = newUser(5);
        InputFile file = upload(owner, "plain.png");
        List<ResolvedInput> resolved = inputs.resolve(owner, List.of(new InputUse(InputRole.IMAGE, InputRef.file(file.getId()))));

        assertThat(settlement.accept(newJob(owner, 12L), null, resolved)).isFalse();

        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(owner)).isEmpty();
        assertThat(inputRowCount(file.getId())).isZero();
        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt()).isEqualTo(file.getExpiresAt());
    }

    @Test
    void delete_givenAFileAJobUses_keepsTheJobsRecordAndItsLinkBecomesNull() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "plain.png");
        GenerationJob job = jobWith(owner, InputRole.IMAGE, InputRef.file(file.getId()));

        inputs.delete(owner, file.getId(), null);

        // The object is gone at once, and the file is no longer its owner's to read.
        assertThat(storage.list("inputs/" + file.getId() + "/")).isEmpty();
        assertRefused(() -> inputs.get(owner, file.getId()), 404, InputRefusedException.CODE_FILE_NOT_FOUND);
        assertThat(inputs.list(owner, null, null)).isEmpty();
        // The job still says what it was given; there is just nothing to link to.
        assertThat(jobReads.get(owner, job.getId()).inputs()).singleElement().satisfies(input -> {
            assertThat(input.fileId()).isEqualTo(file.getId());
            assertThat(input.role()).isEqualTo("image");
            assertThat(input.url()).isNull();
        });
        assertThat(files.findById(file.getId()).orElseThrow().getDeletedAt()).isNotNull();
        // And it cannot be named in a new request.
        assertRefused(() -> inputs.resolve(owner, List.of(new InputUse(InputRole.IMAGE, InputRef.file(file.getId())))),
                404, InputRefusedException.CODE_FILE_NOT_FOUND);
    }

    // ===== Retention ===================================================

    @Test
    void run_givenAnUnusedFilePastItsExpiry_deletesItsObjectAndRow() {
        UUID owner = newUser(0);
        InputFile expired = upload(owner, "plain.png");
        expire(expired, Instant.now().minusSeconds(60));
        InputFile fresh = upload(owner, "plain.jpg");

        DataRetentionJob.Summary summary = DataRetentionJob.run(retention, Instant.now());

        assertThat(summary.inputFilesDeleted()).isPositive();
        assertThat(summary.inputFilesFailed()).isZero();
        assertThat(files.findById(expired.getId())).isEmpty();
        assertThat(storage.list("inputs/" + expired.getId() + "/")).isEmpty();
        // One whose time has not come stays, object and row.
        assertThat(files.findById(fresh.getId())).isPresent();
        assertThat(storage.list("inputs/" + fresh.getId() + "/")).hasSize(1);
        assertThat(DataRetentionJob.run(retention, Instant.now()).inputFilesDeleted()).isZero();
    }

    @Test
    void run_givenAFileAJobUses_keepsItHoweverOldItIs() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "plain.png");
        jobWith(owner, InputRole.IMAGE, InputRef.file(file.getId()));
        jdbc.update("UPDATE input_files SET created_at = now() - interval '60 days' WHERE id = ?", file.getId());

        DataRetentionJob.run(retention, Instant.now().plus(Duration.ofDays(30)));

        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt()).isNull();
        assertThat(storage.list("inputs/" + file.getId() + "/")).hasSize(1);
        assertThat(inputs.get(owner, file.getId()).getId()).isEqualTo(file.getId());
    }

    @Test
    void run_givenThePurgeOfTheOnlyJobThatUsesAFile_releasesItWithAFreshExpiryThenDeletesIt() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "plain.png");
        GenerationJob job = jobWith(owner, InputRole.IMAGE, InputRef.file(file.getId()));
        endAndAge(job, 91);
        Instant now = Instant.now();

        DataRetentionJob.Summary first = DataRetentionJob.run(retention, now);

        // The job is purged: it no longer says what it was given.
        assertThat(first.jobsPurged()).isPositive();
        assertThat(jobs.findById(job.getId()).orElseThrow().getPurgedAt()).isNotNull();
        assertThat(jobReads.get(owner, job.getId()).inputs()).isEmpty();
        assertThat(inputRowCount(file.getId())).isZero();
        // The file is unused again, with a full new period before it goes. Not deleted the same night.
        InputFile released = files.findById(file.getId()).orElseThrow();
        assertThat(released.getExpiresAt()).isCloseTo(now.plus(UNUSED_TTL), within(1, ChronoUnit.SECONDS));
        assertThat(storage.list("inputs/" + file.getId() + "/")).hasSize(1);
        // Its owner can still use it in that period.
        assertThat(inputs.resolve(owner, List.of(new InputUse(InputRole.IMAGE, InputRef.file(file.getId()))))).hasSize(1);

        DataRetentionJob.run(retention, now.plus(UNUSED_TTL).plusSeconds(60));

        assertThat(files.findById(file.getId())).isEmpty();
        assertThat(storage.list("inputs/" + file.getId() + "/")).isEmpty();
    }

    @Test
    void run_givenASavedJobOlderThan90Days_keepsTheInputItUses() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "gps.webp");
        GenerationJob job = jobWith(owner, InputRole.IMAGE, InputRef.file(file.getId()));
        endAndAge(job, 200);
        saves.save(owner, job.getId(), null);

        DataRetentionJob.run(retention, Instant.now());
        DataRetentionJob.run(retention, Instant.now().plus(Duration.ofDays(400)));

        // The saved job is kept, so its input is: object, row, record and link.
        assertThat(jobs.findById(job.getId()).orElseThrow().getPurgedAt()).isNull();
        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt()).isNull();
        assertThat(storage.list("inputs/" + file.getId() + "/")).hasSize(1);
        assertThat(jobReads.get(owner, job.getId()).inputs()).singleElement()
                .satisfies(input -> assertThat(InputFixtures.fetch(input.url()).statusCode()).isEqualTo(200));

        // Un-saved, the job goes at the next run and its file is released.
        saves.unsave(owner, job.getId(), null);
        Instant now = Instant.now();
        DataRetentionJob.run(retention, now);
        assertThat(jobs.findById(job.getId()).orElseThrow().getPurgedAt()).isNotNull();
        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt())
                .isCloseTo(now.plus(UNUSED_TTL), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void purge_givenAFileTwoJobsUse_releasesItOnlyWithTheSecond() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "plain.png");
        GenerationJob first = jobWith(owner, InputRole.IMAGE, InputRef.file(file.getId()));
        GenerationJob second = jobWith(owner, InputRole.REFERENCE_IMAGE, InputRef.file(file.getId()));
        endAndAge(first, 1);
        endAndAge(second, 1);

        assertThat(retention.purgeJob(first.getId(), Instant.now())).isTrue();

        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt()).isNull();
        assertThat(jobReads.get(owner, second.getId()).inputs()).singleElement()
                .satisfies(input -> assertThat(input.url()).isNotNull());

        assertThat(retention.purgeJob(second.getId(), Instant.now())).isTrue();

        assertThat(files.findById(file.getId()).orElseThrow().getExpiresAt()).isNotNull();
    }

    @Test
    void purge_givenAFileItsOwnerAlreadyDeleted_dropsItsRowWithTheLastJob() {
        UUID owner = newUser(50_00);
        InputFile file = upload(owner, "plain.png");
        GenerationJob job = jobWith(owner, InputRole.IMAGE, InputRef.file(file.getId()));
        inputs.delete(owner, file.getId(), null);
        endAndAge(job, 1);
        assertThat(files.findById(file.getId())).isPresent();

        assertThat(retention.purgeJob(job.getId(), Instant.now())).isTrue();

        assertThat(files.findById(file.getId())).isEmpty();
    }

    @Test
    void purge_givenAJobWhoseOutputAnotherJobUses_thatInputsLinkBecomesNull() {
        UUID owner = newUser(50_00);
        GenerationJob source = doneImageJob(owner, InputFixtures.image("plain.png"));
        GenerationJob animated = jobWith(owner, InputRole.FIRST_FRAME, InputRef.jobOutput(source.getId(), 0));
        assertThat(jobReads.get(owner, animated.getId()).inputs().get(0).url()).isNotNull();

        assertThat(retention.purgeJob(source.getId(), Instant.now())).isTrue();

        assertThat(jobReads.get(owner, animated.getId()).inputs()).singleElement().satisfies(input -> {
            assertThat(input.role()).isEqualTo("first_frame");
            assertThat(input.jobOutput().jobId()).isEqualTo(source.getId());
            assertThat(input.url()).isNull();
        });
    }

    @Test
    void run_givenStorageThatCannotDelete_keepsTheRowAndTriesAgainNextRun() {
        UUID owner = newUser(0);
        InputFile file = upload(owner, "plain.png");
        expire(file, Instant.now().minusSeconds(60));
        doThrow(new IllegalStateException("storage is down")).when(storage).deletePrefix(eq("inputs/" + file.getId() + "/"));

        DataRetentionJob.Summary failing = DataRetentionJob.run(retention, Instant.now());

        assertThat(failing.inputFilesFailed()).isEqualTo(1);
        assertThat(files.findById(file.getId())).isPresent();

        reset(storage);
        DataRetentionJob.Summary recovered = DataRetentionJob.run(retention, Instant.now());

        assertThat(recovered.inputFilesFailed()).isZero();
        assertThat(recovered.inputFilesDeleted()).isPositive();
        assertThat(files.findById(file.getId())).isEmpty();
        assertThat(storage.list("inputs/" + file.getId() + "/")).isEmpty();
    }

    // ===== Closing an account ==========================================

    @Test
    void close_givenFilesUsedAndUnused_deletesEveryOneAndForgetsTheTerms() {
        UUID owner = newUser(50_00);
        UUID bystander = newUser(0);
        InputFile unused = upload(owner, "plain.png");
        InputFile used = upload(owner, "plain.jpg");
        InputFile usedBySaved = upload(owner, "plain.webp");
        InputFile deleted = upload(owner, "gps.png");
        GenerationJob job = jobWith(owner, InputRole.IMAGE, InputRef.file(used.getId()));
        GenerationJob saved = jobWith(owner, InputRole.IMAGE, InputRef.file(usedBySaved.getId()));
        GenerationJob usedDeleted = jobWith(owner, InputRole.IMAGE, InputRef.file(deleted.getId()));
        for (GenerationJob j : List.of(job, saved, usedDeleted)) endAndAge(j, 1);
        saves.save(owner, saved.getId(), null);
        inputs.delete(owner, deleted.getId(), null);
        InputFile theirs = upload(bystander, "plain.png");

        AccountClosureService.Result result = closure.close(owner, UUID.randomUUID(), "127.0.0.1");

        // Three had an object left. The fourth's went when its owner deleted it, and its row
        // went with the purge of the job that still recorded it.
        assertThat(result.inputFilesDeleted()).isEqualTo(3);
        assertThat(result.inputFilesNotDeleted()).isZero();
        assertThat(files.findByUserId(owner)).isEmpty();
        for (InputFile f : List.of(unused, used, usedBySaved, deleted)) {
            assertThat(storage.list("inputs/" + f.getId() + "/")).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job_inputs i JOIN generation_jobs j "
                + "ON j.id = i.job_id WHERE j.user_id = ?", Long.class, owner)).isZero();
        User closed = users.findById(owner).orElseThrow();
        assertThat(closed.getInputsTermsVersion()).isNull();
        assertThat(closed.getInputsTermsAcceptedAt()).isNull();
        // Nobody else's files are touched.
        assertThat(files.findById(theirs.getId())).isPresent();
        assertThat(storage.list("inputs/" + theirs.getId() + "/")).hasSize(1);
        // Closing again finds nothing more to do.
        assertThat(closure.close(owner, UUID.randomUUID(), "127.0.0.1").inputFilesDeleted()).isZero();
    }

    @Test
    void close_givenStorageThatCannotDeleteOneFile_keepsItsRowAndClosingAgainRetries() {
        UUID owner = newUser(0);
        InputFile stuck = upload(owner, "plain.png");
        InputFile fine = upload(owner, "plain.jpg");
        doThrow(new IllegalStateException("storage is down")).when(storage).deletePrefix(eq("inputs/" + stuck.getId() + "/"));

        AccountClosureService.Result first = closure.close(owner, UUID.randomUUID(), "127.0.0.1");

        assertThat(first.inputFilesDeleted()).isEqualTo(1);
        assertThat(first.inputFilesNotDeleted()).isEqualTo(1);
        assertThat(files.findById(stuck.getId())).isPresent();
        assertThat(files.findById(fine.getId())).isEmpty();

        reset(storage);
        AccountClosureService.Result second = closure.close(owner, UUID.randomUUID(), "127.0.0.1");

        assertThat(second.inputFilesDeleted()).isEqualTo(1);
        assertThat(second.inputFilesNotDeleted()).isZero();
        assertThat(files.findByUserId(owner)).isEmpty();
        assertThat(storage.list("inputs/" + stuck.getId() + "/")).isEmpty();
    }

    // ===== Helpers =====================================================

    /** An adult account that has accepted the input terms, with this balance. */
    private UUID newUser(long balanceCents) {
        User u = new User();
        u.setEmail("input-retention-" + UUID.randomUUID() + "@godance.test");
        u.setFullName("Input Tester");
        u.setRole(UserRole.USER);
        u.setAgeStatus(AgeStatus.ADULT);
        u.setInputsTermsVersion(1);
        u.setInputsTermsAcceptedAt(Instant.now());
        UUID id = users.save(u).getId();
        if (balanceCents > 0) {
            ledger.topUp(id, balanceCents, CreditSource.PROMO, "seed-" + id, "seed-" + id, "test seed");
        }
        return id;
    }

    private InputFile upload(UUID owner, String fixture) {
        return inputs.upload(owner, InputFixtures.image(fixture), "127.0.0.1");
    }

    /** A job row not yet saved, as a generation service builds it before accepting it. */
    private GenerationJob newJob(UUID owner, long estimateCents) {
        GenerationJob job = new GenerationJob();
        job.setUserId(owner);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "the same, at dusk", "n", 1));
        job.setEstimatedCostCents(estimateCents);
        job.setRequestId("img_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        return job;
    }

    /** An accepted job that uses one input. */
    private GenerationJob jobWith(UUID owner, InputRole role, InputRef ref) {
        GenerationJob job = newJob(owner, 5L);
        assertThat(settlement.accept(job, null, inputs.resolve(owner, List.of(new InputUse(role, ref))))).isTrue();
        return job;
    }

    /** Ends the job DONE and dates it this many days back, as the purge reads it. */
    private void endAndAge(GenerationJob job, int days) {
        jdbc.update("UPDATE generation_jobs SET status = 'DONE', created_at = now() - make_interval(days => ?) WHERE id = ?",
                days, job.getId());
    }

    private GenerationJob job(UUID owner, Modality modality, JobStatus status, Instant createdAt) {
        GenerationJob job = new GenerationJob();
        job.setUserId(owner);
        job.setModality(modality);
        job.setProvider(Provider.OPENAI);
        job.setModel(modality == Modality.VIDEO ? "sora-2" : "gpt-image-2");
        job.setPrompt(Map.of("prompt", "a lighthouse at dusk", "n", 1));
        job.setStatus(status);
        job.setEstimatedCostCents(5L);
        job.setActualCostCents(5L);
        job.setRequestId("img_" + UUID.randomUUID());
        job.setCreatedAt(createdAt);
        return jobs.save(job);
    }

    /** A finished image job with these stored outputs, as a worker leaves it. */
    private GenerationJob doneImageJob(UUID owner, byte[]... images) {
        GenerationJob job = job(owner, Modality.IMAGE, JobStatus.DONE, Instant.now());
        for (int i = 0; i < images.length; i++) {
            ImageMetadata.Format format = ImageMetadata.sniff(images[i]).orElseThrow();
            String key = "images/" + job.getId() + "/" + i + "." + format.extension();
            storage.upload(key, images[i], format.contentType());
            outputRows.save(new GenerationJobOutput(job.getId(), i, key, format.contentType(), images[i].length, null));
        }
        return job;
    }

    private void expire(InputFile file, Instant at) {
        jdbc.update("UPDATE input_files SET expires_at = ? WHERE id = ?", java.sql.Timestamp.from(at), file.getId());
    }

    private long inputRowCount(UUID fileId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM generation_job_inputs WHERE file_id = ?", Long.class, fileId);
    }

    private long ledgerCount(UUID userId) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, 50)).getTotalElements();
    }

    private static void assertRefused(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InputRefusedException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }
}
