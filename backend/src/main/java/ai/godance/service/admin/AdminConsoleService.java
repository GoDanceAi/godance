package ai.godance.service.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;

/**
 * Blocking core of the admin console's user + overview surface
 * ({@code /v1/admin/users/**}, {@code /v1/admin/usage/summary}).
 * Controllers wrap calls in {@code Mono.fromCallable} per the existing
 * admin-controller pattern; the role gate is
 * {@link ai.godance.security.AdminAuthorizationFilter} — nothing here
 * re-checks the caller's role.
 */
@Service
public class AdminConsoleService {

    /** Search + ledger page sizes — small, support-console scale. */
    public static final int SEARCH_LIMIT = 20;
    public static final int LEDGER_PAGE_SIZE = 20;
    private static final int TOP_N = 5;
    /** Stands in for the acting admin on a role the bootstrap granted. */
    private static final String BOOTSTRAP_ACTOR = "bootstrap:GODANCE_ADMIN_EMAILS";

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final CreditLedgerRepository ledger;
    private final UsageRecordRepository usage;
    private final CreditLedgerService ledgerService;
    private final AuditLogService audit;

    public AdminConsoleService(UserRepository users, ApiKeyRepository apiKeys,
                               CreditLedgerRepository ledger, UsageRecordRepository usage,
                               CreditLedgerService ledgerService, AuditLogService audit) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.ledger = ledger;
        this.usage = usage;
        this.ledgerService = ledgerService;
        this.audit = audit;
    }

    // ===== Users ========================================================

    public record UserView(
            UUID id,
            String email,
            String fullName,
            String role,
            boolean active,
            long creditBalanceCents,
            Long dailyCreditCapCents,
            Long monthlyCreditCapCents,
            long apiKeyCount,
            Instant createdAt
    ) { }

    /**
     * Search by email substring (case-insensitive) or exact UUID.
     * Capped at {@link #SEARCH_LIMIT} rows — this is a support lookup,
     * not a user browser.
     */
    @Transactional(readOnly = true)
    public List<UserView> searchUsers(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String query = q.trim();
        List<User> hits;
        UUID asUuid = tryParseUuid(query);
        if (asUuid != null) {
            hits = users.findById(asUuid).map(List::of).orElse(List.of());
        } else {
            hits = users.findByEmailContainingIgnoreCaseOrderByCreatedAtDesc(
                    query, PageRequest.of(0, SEARCH_LIMIT));
        }
        return hits.stream().map(this::toView).toList();
    }

    public record LedgerEntryView(
            UUID id,
            long deltaCents,
            String source,
            String sourceRef,
            String note,
            Instant occurredAt
    ) { }

    public record LedgerPageResponse(
            List<LedgerEntryView> entries,
            int page,
            int totalPages,
            long totalEntries
    ) { }

    @Transactional(readOnly = true)
    public LedgerPageResponse userLedger(UUID userId, int page) {
        requireUser(userId);
        Page<CreditLedger> result = ledger.findByUserIdOrderByOccurredAtDesc(
                userId, PageRequest.of(Math.max(0, page), LEDGER_PAGE_SIZE));
        return new LedgerPageResponse(
                result.getContent().stream()
                        .map(e -> new LedgerEntryView(e.getId(), e.getDeltaCents(),
                                e.getSource().name(), e.getSourceRef(), e.getNote(), e.getOccurredAt()))
                        .toList(),
                result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    public record AdjustResult(UserView user, LedgerEntryView entry) { }

    /**
     * Manual credit/debit. Delegates the balance mutation to
     * {@link CreditLedgerService#adminAdjust} (append-only, idempotent) and
     * writes the {@code admin.credit.adjusted} audit event in the same
     * transaction. The audit row's {@code userId} is the <b>target</b>
     * account; the acting admin is in {@code eventDetail.adminEmail}.
     */
    @Transactional
    public AdjustResult adjustCredit(UUID userId, long amountCents, String note,
                                     String idempotencyKey, String adminEmail) {
        if (amountCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCents must be non-zero");
        }
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note is required");
        }
        requireUser(userId);
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "admin-adjust-" + UUID.randomUUID()
                : "admin-adjust-" + idempotencyKey.trim();
        CreditLedger entry = ledgerService.adminAdjust(userId, amountCents, key, note.trim(), adminEmail);
        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", adminEmail);
        detail.put("targetUserId", userId.toString());
        detail.put("amountCents", amountCents);
        detail.put("note", note.trim());
        detail.put("ledgerEntryId", entry.getId().toString());
        audit.record(AuditLogService.EVT_ADMIN_CREDIT_ADJUSTED, AuditOutcome.SUCCESS,
                userId, null, detail);
        // incrementBalance is a bulk UPDATE that bypasses the persistence
        // context, so the User entity cached in this transaction still has
        // the pre-adjust balance. Report the authoritative ledger SUM instead
        // (which sees the uncommitted insert on this connection).
        User refreshed = requireUser(userId);
        return new AdjustResult(toView(refreshed, ledger.sumDeltaForUser(userId)),
                new LedgerEntryView(entry.getId(), entry.getDeltaCents(),
                        entry.getSource().name(), entry.getSourceRef(), entry.getNote(),
                        entry.getOccurredAt()));
    }

    // ===== Roles ========================================================

    /**
     * Grants {@code role} to an account, or revokes its role by setting
     * {@link UserRole#USER}. Takes effect on the account's next request:
     * see {@link UserRepository#changeRole}.
     *
     * <p>Nobody can change their own role. Only an admin reaches this, so
     * an admin always remains after a change: the last one can't be
     * demoted. Two admins demoting each other at the same instant is the
     * one way around that; {@link AdminBootstrap} recovers from it.
     *
     * <p>Setting the role an account already holds does nothing: no session
     * ends and no audit row is written.
     */
    @Transactional
    public UserView changeRole(UUID userId, UserRole role, UUID actorId, String actorEmail, String ip) {
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role is required");
        }
        if (userId.equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you cannot change your own role");
        }
        User u = requireUser(userId);
        if (u.getRole() == role) {
            return toView(u);
        }
        if (role != UserRole.USER && !(u.isActive() && u.isEmailVerified())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a staff role needs an active account with a verified email");
        }
        if (!applyRole(u, role, actorEmail, actorId, ip)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the role just changed; reload and try again");
        }
        return toView(requireUser(userId));
    }

    /**
     * Makes the accounts behind {@code emails} admins if no account can act
     * as one; otherwise does nothing. Returns the emails it promoted.
     */
    @Transactional
    public List<String> bootstrapAdmins(List<String> emails) {
        List<String> lowerCased = emails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (lowerCased.isEmpty() || users.existsByRoleAndActiveTrueAndEmailVerifiedTrue(UserRole.ADMIN)) {
            return List.of();
        }
        List<String> promoted = new ArrayList<>();
        for (User u : users.findActiveVerifiedByLowerEmailIn(lowerCased)) {
            if (applyRole(u, UserRole.ADMIN, BOOTSTRAP_ACTOR, null, null)) promoted.add(u.getEmail());
        }
        return promoted;
    }

    /** The role change and its audit row, in the caller's transaction. False if another change got there first. */
    private boolean applyRole(User u, UserRole role, String actorEmail, UUID actorId, String ip) {
        if (users.changeRole(u.getId(), u.getRole(), role, Instant.now()) == 0) {
            return false;
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", actorEmail);
        detail.put("actorUserId", actorId == null ? null : actorId.toString());
        detail.put("targetUserId", u.getId().toString());
        detail.put("fromRole", u.getRole().name());
        detail.put("toRole", role.name());
        audit.record(AuditLogService.EVT_ADMIN_ROLE_CHANGED, AuditOutcome.SUCCESS, u.getId(), ip, detail);
        return true;
    }

    // ===== Overview =====================================================

    public record DailyPoint(LocalDate date, long revenueCents, long costCents, long requests) { }
    public record TopModel(String modelName, long revenueCents, long costCents, long requests) { }
    public record TopUser(UUID userId, String email, long revenueCents, long requests) { }
    public record UsageSummary(
            LocalDate from,
            LocalDate to,
            List<DailyPoint> days,
            List<TopModel> topModels,
            List<TopUser> topUsers
    ) { }

    /**
     * Revenue-vs-cost series between {@code from} and {@code to}
     * (inclusive calendar days, UTC), zero-filled so the chart has one
     * point per day. Three aggregate queries total.
     */
    @Transactional(readOnly = true)
    public UsageSummary usageSummary(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be <= to");
        }
        if (from.plusDays(370).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window too large (max ~1 year)");
        }
        Instant fromTs = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, DailyPoint> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            byDay.put(d, new DailyPoint(d, 0, 0, 0));
        }
        for (UsageRecordRepository.DailyUsageRow row : usage.aggregateDailyUsage(fromTs, toTs)) {
            byDay.put(row.getDay(), new DailyPoint(row.getDay(),
                    row.getRevenueCents(), row.getCostCents(), row.getRequests()));
        }
        List<TopModel> topModels = usage.topModelsBySpend(fromTs, toTs, TOP_N).stream()
                .map(r -> new TopModel(r.getModelName(), r.getRevenueCents(), r.getCostCents(), r.getRequests()))
                .toList();
        List<TopUser> topUsers = usage.topUsersBySpend(fromTs, toTs, TOP_N).stream()
                .map(r -> new TopUser(r.getUserId(), r.getEmail(), r.getRevenueCents(), r.getRequests()))
                .toList();
        return new UsageSummary(from, to, new ArrayList<>(byDay.values()), topModels, topUsers);
    }

    // ===== Helpers ======================================================

    private User requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
    }

    private UserView toView(User u) {
        return toView(u, u.getCreditBalanceCents());
    }

    private UserView toView(User u, long balanceCents) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getRole().name(),
                u.isActive(), balanceCents, u.getDailyCreditCapCents(),
                u.getMonthlyCreditCapCents(), apiKeys.countByUserId(u.getId()), u.getCreatedAt());
    }

    private static UUID tryParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
