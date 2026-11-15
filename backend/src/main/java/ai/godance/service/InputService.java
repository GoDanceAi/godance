package ai.godance.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.InputsProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobInput;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.InputFile;
import ai.godance.domain.InputRole;
import ai.godance.domain.JobStatus;
import ai.godance.domain.User;
import ai.godance.repository.GenerationJobInputRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ImageMetadata.Format;
import ai.godance.storage.ImageMetadata.Info;
import ai.godance.storage.ImageMetadata.InvalidImageException;
import ai.godance.storage.ImageMetadata.Stripped;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.InputDtos.InputsStatus;
import ai.godance.web.dto.InputDtos.JobInputView;
import ai.godance.web.dto.InputDtos.JobOutputRef;

/**
 * Input files (round 11 brief 01): images an account uploads to use in a
 * generation, and the one place that decides what is allowed.
 *
 * <p><b>Who may use inputs.</b> Inputs must be switched on
 * ({@code godance.inputs.enabled}), the account must be an adult's, and it
 * must have accepted the current input terms. That holds for uploading and
 * for every request that names an input, whether an uploaded file or one of
 * the caller's own job outputs. With inputs switched off nothing is
 * accepted anywhere.
 *
 * <p><b>What is stored.</b> A PNG, JPEG or WebP still image, told by its own
 * bytes, within the size limits, with its metadata removed
 * ({@link ImageMetadata}), under a key that cannot be guessed. Unless people
 * are allowed ({@code godance.inputs.allow-people}), the image is checked
 * for a person first and refused if it shows one or cannot be checked.
 *
 * <p><b>How long.</b> A file nothing uses is deleted after
 * {@code godance.inputs.unused-ttl-hours}. A file a job uses is kept until
 * the last such job is purged, and then gets a fresh expiry. Closing the
 * account deletes every file.
 *
 * <p><b>Privacy.</b> An input is never public and only its owner reads it.
 * No image is logged or put in an audit entry: audit rows carry sizes,
 * types and reasons.
 *
 * <p>Briefs 03, 04 and 05 use two methods: {@link #resolve} turns the
 * references of a request into images before anything is charged, and
 * {@link #attach} records them in the transaction that creates the job
 * ({@link GenerationJobSettlement#accept(GenerationJob, UUID, List)}).
 */
@Service
@EnableConfigurationProperties(InputsProperties.class)
public class InputService {

    private static final Logger log = LoggerFactory.getLogger(InputService.class);

    /** Largest page {@code limit} accepts, and the page size without one. */
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 20;

    /** Where inputs live in the bucket: {@code inputs/<file id>/<random>.<ext>}. Never under a job's prefix. */
    static final String KEY_PREFIX = "inputs/";

    /** Files per batch of the nightly run, and the most batches in one run. */
    static final int EXPIRY_BATCH = 100;
    static final int MAX_EXPIRY_BATCHES = 200;

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID HIGHEST_UUID = new UUID(-1L, -1L);
    private static final UUID LOWEST_UUID = new UUID(0L, 0L);

    private final InputsProperties props;
    private final UserRepository users;
    private final InputFileRepository files;
    private final GenerationJobInputRepository jobInputs;
    private final GenerationJobRepository jobs;
    private final GenerationJobOutputRepository outputs;
    private final ObjectStorageClient storage;
    private final AuditLogService audit;
    private final ObjectProvider<InputPersonCheck> personCheck;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public InputService(InputsProperties props, UserRepository users, InputFileRepository files,
                        GenerationJobInputRepository jobInputs, GenerationJobRepository jobs,
                        GenerationJobOutputRepository outputs, ObjectStorageClient storage,
                        AuditLogService audit, ObjectProvider<InputPersonCheck> personCheck,
                        PlatformTransactionManager txManager) {
        this.props = props;
        this.users = users;
        this.files = files;
        this.jobInputs = jobInputs;
        this.jobs = jobs;
        this.outputs = outputs;
        this.storage = storage;
        this.audit = audit;
        this.personCheck = personCheck;
        this.tx = new TransactionTemplate(txManager);
    }

    // ===== Who may use inputs =========================================

    /** Whether inputs are switched on at all. */
    public boolean enabled() {
        return props.enabled();
    }

    /** The largest file an upload may be, in bytes. */
    public long maxBytes() {
        return props.maxBytes().toBytes();
    }

    /** {@code inputs} on {@code GET /v1/auth/me}. */
    public InputsStatus statusFor(User user) {
        boolean accepted = acceptedCurrentTerms(user);
        return new InputsStatus(props.enabled(), props.termsVersion(), accepted,
                accepted ? user.getInputsTermsAcceptedAt() : null, props.allowPeople());
    }

    /** Refuses with {@code inputs_disabled} while inputs are switched off. Touches nothing. */
    public void requireEnabled() {
        if (!props.enabled()) throw InputRefusedException.disabled();
    }

    /**
     * Refuses unless this account may use inputs, checking in order: inputs
     * switched on, an adult's account, the current terms accepted. Every
     * request that names an input goes through this before anything else.
     */
    public void requireMayUse(UUID userId) {
        requireEnabled();
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account not found"));
        if (user.getAgeStatus() != AgeStatus.ADULT) throw InputRefusedException.notAllowedForAccount();
        if (!acceptedCurrentTerms(user)) throw InputRefusedException.termsRequired(props.termsVersion());
    }

    /** {@link #requireMayUse} for an upload: a refusal of the account is audited. */
    public void requireMayUpload(UUID userId, String ip) {
        try {
            requireMayUse(userId);
        } catch (InputRefusedException e) {
            // Switched off is not an event: there is nothing to refuse.
            if (!InputRefusedException.CODE_DISABLED.equals(e.code())) auditRefusal(userId, ip, e.code(), null);
            throw e;
        }
    }

    private boolean acceptedCurrentTerms(User user) {
        Integer accepted = user.getInputsTermsVersion();
        return accepted != null && accepted >= props.termsVersion();
    }

    /**
     * Records that the account accepts the input terms. {@code version} must
     * be the current one. Accepting again changes nothing. An account that
     * is not an adult's cannot accept.
     */
    @Transactional
    public void acceptTerms(UUID userId, Integer version, String ip) {
        requireEnabled();
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account not found"));
        if (user.getAgeStatus() != AgeStatus.ADULT) throw InputRefusedException.notAllowedForAccount();
        if (version == null || version != props.termsVersion()) {
            throw InputRefusedException.termsVersion(props.termsVersion());
        }
        if (acceptedCurrentTerms(user)) return;
        user.setInputsTermsVersion(version);
        user.setInputsTermsAcceptedAt(Instant.now());
        users.save(user);
        audit.record(AuditLogService.EVT_INPUTS_TERMS_ACCEPTED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("version", version));
    }

    // ===== Uploading ==================================================

    /**
     * Stores one uploaded image as the caller's input file.
     *
     * <p>The checks run in this order, each with its own code: inputs
     * switched on, an adult's account, the terms accepted
     * ({@link #requireMayUse}); the size in bytes; the type, read from the
     * bytes; the size in pixels; the account's file limit; the person check.
     * Only then is anything stored, with its metadata removed.
     *
     * @param content the uploaded bytes. A caller that stops reading at
     *                {@link #maxBytes()} plus one passes what it read
     * @throws InputRefusedException for each refusal
     */
    public InputFile upload(UUID userId, byte[] content, String ip) {
        requireMayUpload(userId, ip);
        try {
            return store(userId, content, ip);
        } catch (InputRefusedException e) {
            auditRefusal(userId, ip, e.code(), content == null ? null : (long) content.length);
            throw e;
        }
    }

    /** The refusal for an upload that was longer than {@link #maxBytes()}, audited. */
    public InputRefusedException tooLarge(UUID userId, String ip) {
        InputRefusedException e = tooLargeInBytes();
        auditRefusal(userId, ip, e.code(), null);
        return e;
    }

    private InputRefusedException tooLargeInBytes() {
        return InputRefusedException.tooLarge("The file is larger than "
                + props.maxBytes().toMegabytes() + " MB, the most an input may be.");
    }

    private InputFile store(UUID userId, byte[] content, String ip) {
        if (content == null || content.length == 0) {
            throw InputRefusedException.typeUnsupported("The file is empty.");
        }
        if (content.length > maxBytes()) throw tooLargeInBytes();

        // The type comes from the bytes. What the upload called itself is never read.
        if (ImageMetadata.sniff(content).isEmpty()) {
            throw InputRefusedException.typeUnsupported("An input must be a PNG, JPEG or WebP image.");
        }
        Stripped stripped;
        try {
            stripped = ImageMetadata.strip(content);
        } catch (InvalidImageException e) {
            throw InputRefusedException.typeUnsupported("The image could not be read: " + e.getMessage() + ".");
        }
        Info info = stripped.info();
        if (info.animated()) {
            throw InputRefusedException.typeUnsupported("An input must be a still image, not an animation.");
        }
        if (info.pixels() > props.maxPixels()) {
            throw InputRefusedException.tooLarge("The image is " + info.width() + " × " + info.height()
                    + " pixels; an input may have at most " + props.maxMegapixels() + " megapixels.");
        }
        if (files.countLive(userId) >= props.maxFilesPerAccount()) {
            throw InputRefusedException.limitReached(props.maxFilesPerAccount());
        }

        Format format = info.format();
        byte[] image = stripped.bytes();
        Boolean containsPerson = checkForPerson(userId, image, format.contentType());

        UUID id = UUID.randomUUID();
        String key = KEY_PREFIX + id + "/" + randomName() + "." + format.extension();
        // To the microsecond, which is what the database keeps: the row reads back as it was written.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        storage.upload(key, image, format.contentType());
        InputFile saved;
        try {
            saved = files.save(new InputFile(id, userId, key, format.contentType(), image.length,
                    info.width(), info.height(), containsPerson, now, now.plus(props.unusedTtl())));
        } catch (RuntimeException e) {
            // Never leave an object no row knows about.
            deleteObject(id);
            throw e;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileId", id.toString());
        detail.put("bytes", image.length);
        detail.put("contentType", format.contentType());
        detail.put("width", info.width());
        detail.put("height", info.height());
        detail.put("containsPerson", containsPerson);
        audit.record(AuditLogService.EVT_INPUT_UPLOADED, AuditOutcome.SUCCESS, userId, ip, detail);
        return saved;
    }

    /**
     * The person check. With people allowed it does not run and the answer
     * is null. Otherwise it must say "no person": a person is refused, and so
     * is an image nothing could check, which is every image while no
     * {@link InputPersonCheck} is present.
     */
    private Boolean checkForPerson(UUID userId, byte[] image, String contentType) {
        if (props.allowPeople()) return null;
        InputPersonCheck check = personCheck.getIfAvailable();
        InputPersonCheck.Verdict verdict = InputPersonCheck.Verdict.UNAVAILABLE;
        if (check != null) {
            try {
                verdict = check.check(userId, image, contentType);
            } catch (RuntimeException e) {
                log.warn("The person check failed for an upload by {}: {}", userId, e.toString());
            }
        }
        if (verdict == null) verdict = InputPersonCheck.Verdict.UNAVAILABLE;
        return switch (verdict) {
            case NO_PERSON -> Boolean.FALSE;
            case PERSON -> throw InputRefusedException.personNotAllowed();
            case UNAVAILABLE -> throw InputRefusedException.checkUnavailable();
        };
    }

    private void auditRefusal(UUID userId, String ip, String reason, Long bytes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (bytes != null) detail.put("bytes", bytes);
        try {
            audit.recordInNewTransaction(AuditLogService.EVT_INPUT_REFUSED, AuditOutcome.FAILURE, userId, ip, detail);
        } catch (RuntimeException e) {
            log.warn("Could not audit a refused input for {}: {}", userId, e.toString());
        }
    }

    private String randomName() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ===== Reading, listing, deleting =================================

    /** One of the caller's files; {@code file_not_found} for an unknown id, another user's, or a deleted one. */
    public InputFile get(UUID userId, UUID fileId) {
        return files.findLive(fileId, userId).orElseThrow(InputRefusedException::fileNotFound);
    }

    /**
     * The caller's files, newest first. {@code before} is one of the
     * caller's file ids (400 otherwise) and returns only files older than it.
     */
    public List<InputFile> list(UUID userId, Integer limit, UUID before) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        Instant beforeCreatedAt = END_OF_TIME;
        UUID beforeId = HIGHEST_UUID;
        if (before != null) {
            InputFile cursor = files.findLive(before, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "before must be the id of one of your files"));
            beforeCreatedAt = cursor.getCreatedAt();
            beforeId = cursor.getId();
        }
        return files.findPage(userId, beforeCreatedAt, beforeId, pageSize);
    }

    /** A short-lived signed link to one of the caller's files. */
    public String contentUrl(UUID userId, UUID fileId) {
        return storage.presignedUrl(get(userId, fileId).getStorageKey(), props.signedUrlTtl());
    }

    /** An image and its type. */
    public record InputContent(byte[] bytes, String contentType) { }

    /**
     * The bytes of one of the caller's files, read from storage. For a
     * request that names the file by its {@code /v1/files/{id}/content}
     * address (images in chat, brief 02): the file is never fetched over the
     * network. The account must be allowed to use inputs, and the file must
     * pass the people rule as it stands now.
     */
    public InputContent content(UUID userId, UUID fileId) {
        requireMayUse(userId);
        InputFile file = get(userId, fileId);
        requirePeopleRule(file);
        return new InputContent(storage.download(file.getStorageKey()), file.getContentType());
    }

    /**
     * Deletes one of the caller's files: the object goes at once. A job that
     * used it keeps its record, and that input then reads back without a
     * link. Works while inputs are switched off, so nobody is locked out of
     * deleting what they uploaded.
     */
    public void delete(UUID userId, UUID fileId, String ip) {
        tx.executeWithoutResult(status -> {
            InputFile file = files.lockLive(fileId, userId).orElseThrow(InputRefusedException::fileNotFound);
            // Storage first: if it fails the row is untouched and the delete can be tried again.
            deleteObject(file.getId());
            if (files.isInUse(file.getId())) {
                files.markDeleted(file.getId(), Instant.now());
            } else {
                files.deleteRow(file.getId());
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fileId", file.getId().toString());
            detail.put("bytes", file.getBytes());
            audit.record(AuditLogService.EVT_INPUT_DELETED, AuditOutcome.SUCCESS, userId, ip, detail);
        });
    }

    /** Deletes the file's object. Throws when storage does, so no row is ever dropped while its object remains. */
    private void deleteObject(UUID fileId) {
        storage.deletePrefix(KEY_PREFIX + fileId + "/");
    }

    // ===== Pointing at an input =======================================

    /** One input a request names: what it is to the job, and where it comes from. */
    public record InputUse(InputRole role, InputRef ref) { }

    /**
     * An input a request named, checked and loaded: the image itself, its
     * type and size, and what {@link #attach} records. Exactly one of
     * {@code fileId} and ({@code sourceJobId}, {@code sourceIndex}) is set.
     */
    public record ResolvedInput(
            InputRole role,
            UUID fileId,
            UUID sourceJobId,
            Integer sourceIndex,
            String storageKey,
            String contentType,
            int width,
            int height,
            byte[] bytes
    ) {
        /** The reference this came from, as the API writes it. */
        public InputRef ref() {
            return fileId != null ? InputRef.file(fileId) : InputRef.jobOutput(sourceJobId, sourceIndex);
        }
    }

    /**
     * Checks every input a request names and loads its image, before
     * anything is charged. Writes nothing.
     *
     * <ul>
     *   <li>The account must be allowed to use inputs ({@link #requireMayUse}).</li>
     *   <li>A {@code file_id} must be one of the caller's files that still
     *       exists, and must pass the people rule as it stands now.</li>
     *   <li>A {@code job_output} must be an image output of one of the
     *       caller's own jobs that is DONE and not purged. It was generated
     *       here, so the person check does not apply to it.</li>
     * </ul>
     * Another user's file or job answers 404, exactly as one that does not
     * exist.
     *
     * @return the inputs in the order given; empty for none, with no checks run
     */
    public List<ResolvedInput> resolve(UUID userId, List<InputUse> uses) {
        if (uses == null || uses.isEmpty()) return List.of();
        requireMayUse(userId);
        List<ResolvedInput> resolved = new ArrayList<>(uses.size());
        for (InputUse use : uses) {
            InputRef ref = requireOneReference(use.ref());
            resolved.add(ref.fileId() != null ? resolveFile(userId, use.role(), ref.fileId())
                    : resolveJobOutput(userId, use.role(), ref.jobOutput()));
        }
        return List.copyOf(resolved);
    }

    private ResolvedInput resolveFile(UUID userId, InputRole role, UUID fileId) {
        InputFile file = usableFile(userId, fileId);
        byte[] bytes = storage.download(file.getStorageKey());
        return new ResolvedInput(role, file.getId(), null, null, file.getStorageKey(), file.getContentType(),
                file.getWidth(), file.getHeight(), bytes);
    }

    /** One of the caller's files that still exists and passes the people rule as it stands now. */
    private InputFile usableFile(UUID userId, UUID fileId) {
        InputFile file = get(userId, fileId);
        requirePeopleRule(file);
        return file;
    }

    /**
     * What {@link #inspect} knows about an input without loading it. For an
     * uploaded file that is its type, its shape and its size in bytes. For a
     * job output only the type is on record: the shape and size are null.
     */
    public record InputFacts(InputRole role, InputRef ref, String contentType,
                             Integer width, Integer height, Long sizeBytes) { }

    /**
     * {@link #resolve} without loading any image: every check that method
     * makes of who may use an input and whether it is the caller's, for a
     * request that will run later (a row of a recipe batch). Writes nothing
     * and reads nothing from storage. The row's job runs {@link #resolve}
     * itself when it starts, so anything that changes in between is still
     * caught there.
     *
     * @return what is on record about each input, in the order given
     */
    public List<InputFacts> inspect(UUID userId, List<InputUse> uses) {
        if (uses == null || uses.isEmpty()) return List.of();
        requireMayUse(userId);
        List<InputFacts> facts = new ArrayList<>(uses.size());
        for (InputUse use : uses) {
            InputRef ref = requireOneReference(use.ref());
            if (ref.fileId() != null) {
                InputFile file = usableFile(userId, ref.fileId());
                facts.add(new InputFacts(use.role(), ref, file.getContentType(), file.getWidth(), file.getHeight(),
                        file.getBytes()));
            } else {
                GenerationJobOutput output = ownOutput(userId, ref.jobOutput());
                // A video output is not an input; resolve() finds that out by reading the file.
                if (output.getContentType() == null || !output.getContentType().startsWith("image/")) {
                    throw InputRefusedException.jobOutputNotFound();
                }
                facts.add(new InputFacts(use.role(), ref, output.getContentType(), null, null, null));
            }
        }
        return List.copyOf(facts);
    }

    /** The reference, when it names exactly one thing: a file or a job output. */
    private static InputRef requireOneReference(InputRef ref) {
        boolean isFile = ref != null && ref.fileId() != null;
        boolean isOutput = ref != null && ref.jobOutput() != null;
        if (isFile == isOutput) {
            throw InputRefusedException.invalidReference(
                    "an input is either {\"file_id\": …} or {\"job_output\": {\"job_id\": …, \"index\": …}}");
        }
        return ref;
    }

    /**
     * With people not allowed, only a file the check cleared may be used. A
     * file uploaded while people were allowed was never checked, so it stops
     * being usable the moment they are not.
     */
    private void requirePeopleRule(InputFile file) {
        if (!props.allowPeople() && !Boolean.FALSE.equals(file.getContainsPerson())) {
            throw InputRefusedException.personNotAllowed();
        }
    }

    /** The stored output a reference names: one of the caller's own jobs, DONE and not purged. */
    private GenerationJobOutput ownOutput(UUID userId, JobOutputRef ref) {
        if (ref.jobId() == null || ref.index() == null || ref.index() < 0) {
            throw InputRefusedException.invalidReference("job_output needs a job_id and an index of 0 or more");
        }
        GenerationJob job = jobs.findById(ref.jobId())
                .filter(j -> j.getUserId().equals(userId))
                .filter(j -> j.getStatus() == JobStatus.DONE && j.getPurgedAt() == null)
                .orElseThrow(InputRefusedException::jobOutputNotFound);
        return outputs.findByJobIdOrderByOutputIndexAsc(job.getId()).stream()
                .filter(o -> o.getOutputIndex() == ref.index())
                .findFirst()
                .orElseThrow(InputRefusedException::jobOutputNotFound);
    }

    private ResolvedInput resolveJobOutput(UUID userId, InputRole role, JobOutputRef ref) {
        GenerationJobOutput output = ownOutput(userId, ref);
        byte[] bytes;
        Info info;
        try {
            bytes = storage.download(output.getStorageKey());
            info = ImageMetadata.inspect(bytes);
        } catch (InvalidImageException | IllegalStateException e) {
            // A video, or a file that is no longer there.
            throw InputRefusedException.jobOutputNotFound();
        }
        return new ResolvedInput(role, null, output.getJobId(), ref.index(), output.getStorageKey(),
                info.format().contentType(), info.width(), info.height(), bytes);
    }

    /**
     * Records a job's inputs. Runs in the transaction that creates the job
     * and takes its pre-debit, so a refusal here leaves no job and no ledger
     * row. Each uploaded file is marked as in use, which stops its expiry;
     * if one was deleted since {@link #resolve} read it, the whole job is
     * refused with {@code file_not_found}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(GenerationJob job, List<ResolvedInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return;
        requireMayUse(job.getUserId());
        Set<UUID> fileIds = new LinkedHashSet<>();
        for (ResolvedInput input : inputs) {
            if (input.fileId() != null) fileIds.add(input.fileId());
        }
        if (!fileIds.isEmpty() && files.markInUse(fileIds, job.getUserId()) != fileIds.size()) {
            throw InputRefusedException.fileNotFound();
        }
        for (int position = 0; position < inputs.size(); position++) {
            ResolvedInput input = inputs.get(position);
            if (input.fileId() != null) {
                jobInputs.insertFile(job.getId(), position, input.role().wire(), input.fileId());
            } else {
                jobInputs.insertJobOutput(job.getId(), position, input.role().wire(),
                        input.sourceJobId(), input.sourceIndex());
            }
        }
    }

    // ===== Files a recipe batch holds =================================

    /**
     * Holds the files a recipe batch's rows name, so the nightly run does
     * not delete one while the batch waits (round 12). Runs in the
     * transaction that creates the batch: if a file was deleted since
     * {@link #inspect} read it, the batch is refused with
     * {@code file_not_found} and nothing is written.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForBatch(UUID batchId, UUID userId, Set<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return;
        requireMayUse(userId);
        if (files.markInUse(fileIds, userId) != fileIds.size()) {
            throw InputRefusedException.fileNotFound();
        }
        for (UUID fileId : fileIds) files.holdForBatch(batchId, fileId);
    }

    /**
     * Lets go of the files a batch held: the batch has ended. A file one of
     * its jobs used is still in use, by that job, until the job is purged.
     * A file nothing uses any more is unused from now: it gets a fresh
     * expiry, or, when its owner had already deleted it, its row goes.
     */
    @Transactional
    public void releaseBatch(UUID batchId, Instant now) {
        List<UUID> held = files.findHeldByBatch(batchId);
        if (files.releaseBatch(batchId) == 0 || held.isEmpty()) return;
        files.deleteGoneAndUnused(held);
        files.expireUnused(held, now.plus(props.unusedTtl()));
    }

    /** A signed link to a resolved input for a provider that fetches inputs by URL. Short-lived, never public. */
    public String providerUrl(ResolvedInput input) {
        return storage.presignedUrl(input.storageKey(), props.providerUrlTtl());
    }

    // ===== A job's inputs, as it reads back ===========================

    /** {@code inputs} of one job. Empty for a job with none, and after its purge. */
    public List<JobInputView> viewsFor(UUID jobId) {
        return viewsFor(List.of(jobId)).getOrDefault(jobId, List.of());
    }

    /**
     * {@code inputs} of several jobs, in three reads. Each link is signed
     * now; it is null when the file has been deleted, or the job whose
     * output it was has been purged.
     */
    public Map<UUID, List<JobInputView>> viewsFor(List<UUID> jobIds) {
        if (jobIds.isEmpty()) return Map.of();
        List<GenerationJobInput> rows = jobInputs.findByJobIdInOrderByJobIdAscPositionAsc(jobIds);
        if (rows.isEmpty()) return Map.of();

        Set<UUID> fileIds = new LinkedHashSet<>();
        Set<UUID> sourceJobIds = new LinkedHashSet<>();
        for (GenerationJobInput row : rows) {
            if (row.getFileId() != null) fileIds.add(row.getFileId());
            else sourceJobIds.add(row.getSourceJobId());
        }
        Map<UUID, String> fileKeys = new HashMap<>();
        for (InputFile file : files.findAllById(fileIds)) {
            if (file.getDeletedAt() == null) fileKeys.put(file.getId(), file.getStorageKey());
        }
        // A purged job has no output rows, so a missing row is a file that is gone.
        Map<UUID, Map<Integer, String>> outputKeys = new HashMap<>();
        if (!sourceJobIds.isEmpty()) {
            for (GenerationJobOutput o : outputs.findByJobIdInOrderByJobIdAscOutputIndexAsc(sourceJobIds)) {
                outputKeys.computeIfAbsent(o.getJobId(), k -> new HashMap<>()).put(o.getOutputIndex(), o.getStorageKey());
            }
        }

        Map<UUID, List<JobInputView>> byJob = new HashMap<>();
        for (GenerationJobInput row : rows) {
            String key = row.getFileId() != null
                    ? fileKeys.get(row.getFileId())
                    : outputKeys.getOrDefault(row.getSourceJobId(), Map.of()).get(row.getSourceIndex());
            String url = key == null ? null : storage.presignedUrl(key, props.signedUrlTtl());
            JobInputView view = row.getFileId() != null
                    ? new JobInputView(row.getRole().wire(), row.getFileId(), null, url)
                    : new JobInputView(row.getRole().wire(), null,
                            new JobOutputRef(row.getSourceJobId(), row.getSourceIndex()), url);
            byJob.computeIfAbsent(row.getJobId(), k -> new ArrayList<>()).add(view);
        }
        return byJob;
    }

    // ===== Retention ==================================================

    /**
     * Forgets a purged job's inputs and looks again at the files it used. A
     * file no other job uses is unused from now: it gets a fresh expiry, or,
     * when its owner had already deleted it, its row goes. Runs in the
     * transaction that redacts the job.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseJob(UUID jobId, Instant now) {
        List<UUID> used = jobInputs.findFileIdsByJobId(jobId);
        if (jobInputs.deleteByJobId(jobId) == 0 || used.isEmpty()) return;
        files.deleteGoneAndUnused(used);
        files.expireUnused(used, now.plus(props.unusedTtl()));
    }

    /** How many unused files a pass deleted, and how many it could not (left for the next run). */
    public record ExpiryCount(int deleted, int failed) { }

    /**
     * Deletes every file whose expiry has passed and that nothing uses: the
     * object, then the row. Runs whether or not inputs are switched on, so
     * switching them off never strands a file.
     */
    public ExpiryCount deleteExpired(Instant now) {
        Instant afterExpiresAt = Instant.EPOCH;
        UUID afterId = LOWEST_UUID;
        int deleted = 0;
        int failed = 0;
        for (int batch = 0; batch < MAX_EXPIRY_BATCHES; batch++) {
            // Keyset paging, so a file that fails to delete is passed over this run, not re-read.
            List<InputFile> page = files.findExpired(now, afterExpiresAt, afterId, EXPIRY_BATCH);
            for (InputFile file : page) {
                switch (deleteIfStillExpired(file.getId(), now)) {
                    case DELETED -> deleted++;
                    case FAILED -> failed++;
                    case KEPT -> { }
                }
            }
            if (page.size() < EXPIRY_BATCH) return new ExpiryCount(deleted, failed);
            InputFile last = page.get(page.size() - 1);
            afterExpiresAt = last.getExpiresAt();
            afterId = last.getId();
        }
        log.warn("InputService: stopped after {} expired input files this run; the rest are deleted next run",
                MAX_EXPIRY_BATCHES * EXPIRY_BATCH);
        return new ExpiryCount(deleted, failed);
    }

    private enum Outcome { DELETED, FAILED, KEPT }

    /**
     * The row is locked and checked again before anything is deleted, so a
     * job that starts using the file meanwhile either gets there first and
     * the file stays, or finds it gone and is refused.
     */
    private Outcome deleteIfStillExpired(UUID fileId, Instant now) {
        try {
            Boolean gone = tx.execute(status -> {
                if (files.lockIfExpiredAndUnused(fileId, now).isEmpty()) return false;
                deleteObject(fileId);
                files.deleteRow(fileId);
                return true;
            });
            return Boolean.TRUE.equals(gone) ? Outcome.DELETED : Outcome.KEPT;
        } catch (RuntimeException e) {
            log.warn("InputService: could not delete expired input file {}: {}", fileId, e.getMessage());
            return Outcome.FAILED;
        }
    }

    /** What closing an account did with its files. {@code notDeleted > 0}: close again to retry. */
    public record ClosureCount(int deleted, int notDeleted) { }

    /**
     * Deletes every file of an account, used or not, and every record of
     * their use: closing the account. A file whose object cannot be deleted
     * keeps its row, so closing again tries it again.
     */
    public ClosureCount deleteAllOf(UUID userId) {
        int deleted = 0;
        int notDeleted = 0;
        for (InputFile file : files.findByUserId(userId)) {
            try {
                tx.executeWithoutResult(status -> {
                    deleteObject(file.getId());
                    files.deleteRow(file.getId());
                });
                deleted++;
            } catch (RuntimeException e) {
                log.warn("InputService: could not delete input file {} of a closed account: {}",
                        file.getId(), e.getMessage());
                notDeleted++;
            }
        }
        return new ClosureCount(deleted, notDeleted);
    }
}
