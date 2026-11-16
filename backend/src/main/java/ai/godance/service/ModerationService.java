package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ai.godance.config.CommunityProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.Profile;
import ai.godance.domain.PublishedRecipe;
import ai.godance.domain.PublishedRecipeExample;
import ai.godance.domain.PublishedStatus;
import ai.godance.domain.Report;
import ai.godance.domain.User;
import ai.godance.repository.FollowRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.ProfileRepository;
import ai.godance.repository.PublishedRecipeExampleRepository;
import ai.godance.repository.PublishedRecipeRepository;
import ai.godance.repository.ReportRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.CommunityDtos.MadeFrom;
import ai.godance.web.dto.CommunityDtos.PriorDecision;
import ai.godance.web.dto.CommunityDtos.PublicInputSlot;
import ai.godance.web.dto.CommunityDtos.PublicVariable;
import ai.godance.web.dto.CommunityDtos.QueueItem;
import ai.godance.web.dto.CommunityDtos.ReportRequest;
import ai.godance.web.dto.CommunityDtos.ReviewAuthor;
import ai.godance.web.dto.CommunityDtos.ReviewExample;
import ai.godance.web.dto.CommunityDtos.ReviewRecipe;
import ai.godance.web.dto.CommunityDtos.ReviewReport;

/**
 * Moderation and reports (round 14 brief 01). Publishing is pre-moderated:
 * a submission is public only once a moderator approves it, which copies its
 * examples to public storage and approves its author's profile as the
 * moderator saw it. Rejecting and removing record a reason the author sees.
 * A report on a public recipe puts it back in the queue; it stays public
 * unless a moderator removes it.
 */
@Service
public class ModerationService {

    static final int REASON_MAX = 1_000;
    static final int QUEUE_PROFILES_MAX = 100;

    private static final String WHY_PENDING = "pending";
    private static final String WHY_REPORTED = "reported";
    private static final String WHY_PROFILE = "profile";

    private final PublishedRecipeRepository published;
    private final PublishedRecipeExampleRepository examples;
    private final ProfileRepository profiles;
    private final ReportRepository reports;
    private final UserRepository users;
    private final FollowRepository follows;
    private final GenerationJobOutputRepository outputs;
    private final PublishedExamples copies;
    private final PublishService publishing;
    private final ProfileService profileService;
    private final AuditLogService audit;
    private final CommunityProperties props;
    private final TransactionTemplate tx;

    public ModerationService(PublishedRecipeRepository published, PublishedRecipeExampleRepository examples,
                             ProfileRepository profiles, ReportRepository reports, UserRepository users,
                             FollowRepository follows, GenerationJobOutputRepository outputs,
                             PublishedExamples copies, PublishService publishing, ProfileService profileService,
                             AuditLogService audit, CommunityProperties props, PlatformTransactionManager txManager) {
        this.published = published;
        this.examples = examples;
        this.profiles = profiles;
        this.reports = reports;
        this.users = users;
        this.follows = follows;
        this.outputs = outputs;
        this.copies = copies;
        this.publishing = publishing;
        this.profileService = profileService;
        this.audit = audit;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    // ===== The queue ====================================================

    /**
     * What waits for a moderator: new submissions, oldest first; public
     * recipes with open reports; and profiles with a change to review or an
     * open report. Each with what a review needs: the template and variables,
     * the examples, the author's profile, and prior decisions on the author.
     */
    @Transactional(readOnly = true)
    public List<QueueItem> queue() {
        List<QueueItem> items = new ArrayList<>();
        for (PublishedRecipe r : published.findTop100ByStatusOrderBySubmittedAtAscIdAsc(PublishedStatus.PENDING)) {
            items.add(recipeItem(WHY_PENDING, r));
        }
        for (PublishedRecipe r : published.findReportedPublic()) {
            items.add(recipeItem(WHY_REPORTED, r));
        }
        Set<UUID> profileOwners = new LinkedHashSet<>();
        profiles.findChangesAwaitingReview(QUEUE_PROFILES_MAX).forEach(p -> profileOwners.add(p.getUserId()));
        reports.findTop200ByStatusOrderByCreatedAtAscIdAsc(Report.STATUS_OPEN).stream()
                .filter(r -> Report.KIND_PROFILE.equals(r.getKind()))
                .forEach(r -> profileOwners.add(r.getProfileUserId()));
        for (UUID owner : profileOwners) {
            profiles.findById(owner).ifPresent(p -> items.add(profileItem(p)));
        }
        return items;
    }

    /** The open reports, oldest first. */
    @Transactional(readOnly = true)
    public List<ReviewReport> openReports() {
        List<Report> open = reports.findTop200ByStatusOrderByCreatedAtAscIdAsc(Report.STATUS_OPEN);
        return reportViews(open);
    }

    // ===== Recipes ======================================================

    /**
     * Approves a pending submission: copies its examples to public storage,
     * makes it {@code PUBLIC}, and approves its author's profile as it is now.
     * On a public recipe with open reports, keeps it public and closes them.
     *
     * @throws CommunityRefusedException 404 {@code not_found}; 409
     *         {@code moderation_conflict} for a rejected or removed one, or
     *         {@code example_unavailable} when an example's files are gone
     */
    public ReviewRecipe approve(UUID moderatorId, String publicId, String ip) {
        PublishedRecipe row = find(publicId);
        if (row.getStatus() == PublishedStatus.PUBLIC) {
            return tx.execute(status -> {
                PublishedRecipe locked = lock(row);
                if (locked.getStatus() != PublishedStatus.PUBLIC) throw conflict(locked);
                int resolved = reports.resolveForRecipe(locked.getId(), moderatorId, Instant.now());
                locked.setReviewedBy(moderatorId);
                locked.setReviewedAt(Instant.now());
                published.save(locked);
                audit.record(PublishService.AUDIT_APPROVED, AuditOutcome.SUCCESS, locked.getUserId(), ip, Map.of(
                        "publicId", locked.getPublicId(), "moderatorId", moderatorId.toString(),
                        "kept", true, "reportsResolved", resolved));
                return review(locked);
            });
        }
        if (row.getStatus() != PublishedStatus.PENDING) throw conflict(row);
        // Storage is outside the transaction; the copies are recorded only if the row is still pending.
        List<PublishedExamples.Copied> copied = copies.copy(row);
        try {
            return tx.execute(status -> {
                PublishedRecipe locked = lock(row);
                if (locked.getStatus() != PublishedStatus.PENDING) throw conflict(locked);
                Profile author = profiles.findById(locked.getUserId()).orElseThrow(() -> CommunityRefusedException.of(
                        HttpStatus.CONFLICT, CommunityRefusedException.CODE_WRONG_STATE, "the author has no profile"));
                Instant now = Instant.now();
                copies.record(copied);
                locked.setStatus(PublishedStatus.PUBLIC);
                if (locked.getPublishedAt() == null) locked.setPublishedAt(now);
                locked.setReviewedBy(moderatorId);
                locked.setReviewedAt(now);
                locked.setReason(null);
                published.save(locked);
                approveProfileAsIs(author, now);
                reports.resolveForRecipe(locked.getId(), moderatorId, now);
                audit.record(PublishService.AUDIT_APPROVED, AuditOutcome.SUCCESS, locked.getUserId(), ip, Map.of(
                        "publicId", locked.getPublicId(), "moderatorId", moderatorId.toString(),
                        "examples", copied.size()));
                return review(locked);
            });
        } catch (RuntimeException e) {
            // Nothing was recorded. The copies made for it go, unless another
            // moderator's approval of the same row won: the keys are the same.
            boolean nowPublic = published.findById(row.getId())
                    .map(r -> r.getStatus() == PublishedStatus.PUBLIC).orElse(false);
            if (!nowPublic) copies.discard(row);
            throw e;
        }
    }

    /**
     * Rejects a pending submission with a reason its author sees.
     *
     * @throws CommunityRefusedException 400 {@code invalid_publish} without a
     *         reason; 404; 409 {@code moderation_conflict} unless it is pending
     */
    @Transactional
    public ReviewRecipe reject(UUID moderatorId, String publicId, String reason, String ip) {
        String why = reason(reason);
        PublishedRecipe row = lock(find(publicId));
        if (row.getStatus() != PublishedStatus.PENDING) throw conflict(row);
        Instant now = Instant.now();
        row.setStatus(PublishedStatus.REJECTED);
        row.setReason(why);
        row.setReviewedBy(moderatorId);
        row.setReviewedAt(now);
        published.save(row);
        reports.resolveForRecipe(row.getId(), moderatorId, now);
        audit.record(PublishService.AUDIT_REJECTED, AuditOutcome.SUCCESS, row.getUserId(), ip, Map.of(
                "publicId", row.getPublicId(), "moderatorId", moderatorId.toString()));
        PublishService.afterCommit(() -> copies.delete(row));
        return review(row);
    }

    /**
     * Takes a public or pending recipe down with a reason its author sees;
     * its example copies are deleted.
     *
     * @throws CommunityRefusedException 400 without a reason; 404; 409
     *         {@code moderation_conflict} for one already rejected or removed
     */
    @Transactional
    public ReviewRecipe remove(UUID moderatorId, String publicId, String reason, String ip) {
        String why = reason(reason);
        PublishedRecipe row = lock(find(publicId));
        if (row.getStatus() != PublishedStatus.PUBLIC && row.getStatus() != PublishedStatus.PENDING) {
            throw conflict(row);
        }
        Instant now = Instant.now();
        row.setReviewedBy(moderatorId);
        row.setReviewedAt(now);
        publishing.close(row, why, "moderator", moderatorId, ip);
        reports.resolveForRecipe(row.getId(), moderatorId, now);
        return review(row);
    }

    // ===== Profiles =====================================================

    /** Approves the profile as it is now, and closes its open reports. */
    @Transactional
    public QueueItem approveProfile(UUID moderatorId, String handle, String ip) {
        Profile profile = findProfile(handle);
        Instant now = Instant.now();
        approveProfileAsIs(profile, now);
        reports.resolveForProfile(profile.getUserId(), moderatorId, now);
        audit.record(PublishService.AUDIT_APPROVED, AuditOutcome.SUCCESS, profile.getUserId(), ip, Map.of(
                "handle", profile.getHandle(), "kind", Report.KIND_PROFILE, "moderatorId", moderatorId.toString()));
        return profileItem(profile);
    }

    /**
     * Clears the profile's public display name and link, refuses its
     * owner's current values with a reason they see, and closes its open
     * reports. The handle stays, as do the owner's recipes.
     */
    @Transactional
    public QueueItem removeProfile(UUID moderatorId, String handle, String reason, String ip) {
        String why = reason(reason);
        Profile profile = findProfile(handle);
        Instant now = Instant.now();
        profile.setApprovedDisplayName(null);
        profile.setApprovedLink(null);
        profile.setReviewReason(why);
        profiles.save(profile);
        reports.resolveForProfile(profile.getUserId(), moderatorId, now);
        audit.record(PublishService.AUDIT_REMOVED, AuditOutcome.SUCCESS, profile.getUserId(), ip, Map.of(
                "handle", profile.getHandle(), "kind", Report.KIND_PROFILE, "moderatorId", moderatorId.toString()));
        return profileItem(profile);
    }

    private void approveProfileAsIs(Profile profile, Instant now) {
        profile.setApprovedDisplayName(profile.getDisplayName());
        profile.setApprovedLink(profile.getLink());
        if (profile.getApprovedAt() == null) profile.setApprovedAt(now);
        profile.setReviewReason(null);
        profiles.save(profile);
    }

    // ===== Reports ======================================================

    /**
     * Reports a public recipe (by public id) or a public profile (by handle).
     * An account's second open report on the same target changes nothing.
     *
     * @throws CommunityRefusedException 400 {@code invalid_report}; 404
     *         {@code not_found} for anything that is not public; 429
     *         {@code report_limit_reached}
     */
    @Transactional
    public void report(UUID reporterId, ReportRequest req, String ip) {
        profileService.requireEnabled();
        if (req == null || req.kind() == null) throw invalidReport("kind is published_recipe or profile");
        String kind = req.kind().trim().toLowerCase(Locale.ROOT);
        String why = req.reason() == null ? "" : req.reason().strip();
        if (why.isEmpty() || why.length() > REASON_MAX) {
            throw invalidReport("a report gives a reason of 1 to " + REASON_MAX + " characters");
        }
        if (reports.countByReporterIdAndCreatedAtAfter(reporterId, Instant.now().minus(Duration.ofHours(1)))
                >= props.reportLimitPerHour()) {
            throw CommunityRefusedException.of(HttpStatus.TOO_MANY_REQUESTS, CommunityRefusedException.CODE_REPORT_LIMIT,
                    "you have sent " + props.reportLimitPerHour() + " reports in the last hour; try again later");
        }
        Report report = new Report();
        report.setReporterId(reporterId);
        report.setKind(kind);
        report.setReason(why);
        String target;
        switch (kind) {
            case Report.KIND_PUBLISHED_RECIPE -> {
                PublishedRecipe recipe = published.findByPublicId(req.id() == null ? "" : req.id().trim())
                        .filter(r -> r.getStatus() == PublishedStatus.PUBLIC)
                        .orElseThrow(() -> CommunityRefusedException.notFound("recipe"));
                if (recipe.getUserId().equals(reporterId)) throw invalidReport("you cannot report your own recipe");
                if (reports.existsByReporterIdAndPublishedRecipeIdAndStatus(reporterId, recipe.getId(), Report.STATUS_OPEN)) return;
                report.setPublishedRecipeId(recipe.getId());
                target = recipe.getPublicId();
            }
            case Report.KIND_PROFILE -> {
                Profile profile = profileService.publicProfile(req.id());
                if (profile.getUserId().equals(reporterId)) throw invalidReport("you cannot report your own profile");
                if (reports.existsByReporterIdAndProfileUserIdAndStatus(reporterId, profile.getUserId(), Report.STATUS_OPEN)) return;
                report.setProfileUserId(profile.getUserId());
                target = profile.getHandle();
            }
            default -> throw invalidReport("kind is published_recipe or profile");
        }
        reports.save(report);
        audit.record(PublishService.AUDIT_REPORTED, AuditOutcome.SUCCESS, reporterId, ip,
                Map.of("kind", kind, "target", target));
    }

    // ===== Views ========================================================

    private QueueItem recipeItem(String why, PublishedRecipe r) {
        ReviewAuthor author = author(r.getUserId());
        List<PriorDecision> prior = published.findByUserIdAndIdNotOrderBySubmittedAtDesc(r.getUserId(), r.getId())
                .stream().limit(20)
                .map(p -> new PriorDecision(p.getPublicId(), p.getTitle(), p.getStatus().name(), p.getReason(), p.getReviewedAt()))
                .toList();
        List<ReviewReport> open = reportViews(reports.findByPublishedRecipeIdInAndStatus(List.of(r.getId()), Report.STATUS_OPEN));
        return new QueueItem(why, review(r), author, prior, open);
    }

    private QueueItem profileItem(Profile p) {
        List<PriorDecision> prior = published.findByUserIdAndStatusIn(p.getUserId(), List.of(PublishedStatus.values()))
                .stream().limit(20)
                .map(r -> new PriorDecision(r.getPublicId(), r.getTitle(), r.getStatus().name(), r.getReason(), r.getReviewedAt()))
                .toList();
        List<ReviewReport> open = reportViews(reports.findByProfileUserIdAndStatus(p.getUserId(), Report.STATUS_OPEN));
        return new QueueItem(WHY_PROFILE, null, author(p.getUserId()), prior, open);
    }

    private ReviewAuthor author(UUID userId) {
        User user = users.findById(userId).orElse(null);
        Profile p = profiles.findById(userId).orElse(null);
        return new ReviewAuthor(userId, user == null ? null : user.getEmail(),
                p == null ? null : p.getHandle(), p == null ? null : p.getDisplayName(), p == null ? null : p.getLink(),
                p == null ? null : p.getApprovedDisplayName(), p == null ? null : p.getApprovedLink(),
                p == null ? null : p.getApprovedAt(), follows.countByFolloweeId(userId));
    }

    ReviewRecipe review(PublishedRecipe r) {
        List<PublishedRecipeExample> rows = examples.findByPublishedRecipeIdOrderByPositionAsc(r.getId());
        List<UUID> jobIds = rows.stream().map(PublishedRecipeExample::getSourceJobId).filter(Objects::nonNull).distinct().toList();
        Map<String, GenerationJobOutput> sources = jobIds.isEmpty() ? Map.of()
                : outputs.findByJobIdInOrderByJobIdAscOutputIndexAsc(jobIds).stream()
                        .collect(Collectors.toMap(o -> o.getJobId() + "#" + o.getOutputIndex(), Function.identity()));
        List<ReviewExample> shown = new ArrayList<>();
        for (PublishedRecipeExample e : rows) {
            String url = null;
            if (e.getStorageKey() != null) {
                url = copies.signedUrl(e);
            } else {
                GenerationJobOutput source = sources.get(e.getSourceJobId() + "#" + e.getSourceOutputIndex());
                if (source != null) url = copies.reviewUrl(source.getStorageKey());
            }
            shown.add(new ReviewExample(e.getPosition(), url, e.getContentType(), e.getSourceJobId(), e.getStorageKey() != null));
        }
        MadeFrom madeFrom = null;
        if (r.getMadeFromPublishedId() != null) {
            madeFrom = published.findById(r.getMadeFromPublishedId())
                    .map(s -> new MadeFrom("published", s.getPublicId())).orElse(null);
        } else if (r.getMadeFromGallery() != null) {
            madeFrom = new MadeFrom("gallery", r.getMadeFromGallery());
        }
        return new ReviewRecipe(r.getPublicId(), r.getStatus().name(), r.getTitle(),
                r.getModality().name().toLowerCase(Locale.ROOT), r.getVersionNumber(), r.getTemplate(),
                r.getVariables().stream().map(v -> new PublicVariable(v.get("name"), v.get("default"))).toList(),
                r.getInputs().stream().map(s -> new PublicInputSlot(s.get("slot"), s.get("role"), s.get("hint"))).toList(),
                r.getModel(), r.getParams(), r.getLicence(), r.getTags(), madeFrom, shown, r.getReason(),
                r.getSubmittedAt(), r.getPublishedAt(), r.getReviewedAt());
    }

    private List<ReviewReport> reportViews(List<Report> open) {
        if (open.isEmpty()) return List.of();
        Map<UUID, String> recipeIds = new LinkedHashMap<>();
        List<UUID> recipeRows = open.stream().map(Report::getPublishedRecipeId).filter(Objects::nonNull).distinct().toList();
        if (!recipeRows.isEmpty()) {
            published.findByIdIn(recipeRows).forEach(r -> recipeIds.put(r.getId(), r.getPublicId()));
        }
        Map<UUID, String> handles = new LinkedHashMap<>();
        List<UUID> owners = open.stream().map(Report::getProfileUserId).filter(Objects::nonNull).distinct().toList();
        if (!owners.isEmpty()) profiles.findByUserIdIn(owners).forEach(p -> handles.put(p.getUserId(), p.getHandle()));
        return open.stream().map(r -> new ReviewReport(r.getId(), r.getKind(),
                Report.KIND_PROFILE.equals(r.getKind()) ? handles.get(r.getProfileUserId()) : recipeIds.get(r.getPublishedRecipeId()),
                r.getReason(), r.getReporterId(), r.getCreatedAt())).toList();
    }

    // ===== Helpers ======================================================

    private PublishedRecipe find(String publicId) {
        return published.findByPublicId(publicId == null ? "" : publicId.trim())
                .orElseThrow(() -> CommunityRefusedException.notFound("recipe"));
    }

    private PublishedRecipe lock(PublishedRecipe row) {
        return published.lockById(row.getId()).orElseThrow(() -> CommunityRefusedException.notFound("recipe"));
    }

    private Profile findProfile(String handle) {
        String normalised = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        return profiles.findByHandle(normalised).orElseThrow(() -> CommunityRefusedException.notFound("profile"));
    }

    private static String reason(String raw) {
        String why = raw == null ? "" : raw.strip();
        if (why.isEmpty() || why.length() > REASON_MAX) {
            throw CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_INVALID_PUBLISH,
                    "give a reason of 1 to " + REASON_MAX + " characters; the author sees it");
        }
        return why;
    }

    private static CommunityRefusedException conflict(PublishedRecipe row) {
        CommunityRefusedException e = CommunityRefusedException.of(HttpStatus.CONFLICT,
                CommunityRefusedException.CODE_WRONG_STATE, "the recipe is " + row.getStatus().name().toLowerCase(Locale.ROOT));
        e.getBody().setProperty("status", row.getStatus().name());
        return e;
    }

    private static CommunityRefusedException invalidReport(String detail) {
        return CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_INVALID_REPORT, detail);
    }
}
