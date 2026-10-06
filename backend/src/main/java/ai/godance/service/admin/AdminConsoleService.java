package ai.godance.service.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.ApiKey;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;

/**
 * Blocking core of the admin console's user + overview surface
 * ({@code /v1/admin/users/**}, {@code /v1/admin/usage/summary}).
 * Controllers wrap calls in {@code Mono.fromCallable} per the existing
 * admin-controller pattern; the allowlist gate is
 * {@link ai.godance.security.AdminAuthorizationFilter} — nothing here
 * re-checks admin status.
 */
@Service
public class AdminConsoleService {

    /** Search + ledger page sizes — small, support-console scale. */
    public static final int SEARCH_LIMIT = 20;
    public static final int LEDGER_PAGE_SIZE = 20;
    private static final int TOP_N = 5;

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final CreditLedgerRepository ledger;
    private final UsageRecordRepository usage;
    private final GenerationJobRepository jobs;
    private final CreditLedgerService ledgerService;
    private final AuditLogService audit;
    private final AuditLogEntryRepository auditLogs;

    public AdminConsoleService(UserRepository users, ApiKeyRepository apiKeys,
                               CreditLedgerRepository ledger, UsageRecordRepository usage,
                               GenerationJobRepository jobs,
                               CreditLedgerService ledgerService, AuditLogService audit,
                               AuditLogEntryRepository auditLogs) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.ledger = ledger;
        this.usage = usage;
        this.jobs = jobs;
        this.ledgerService = ledgerService;
        this.audit = audit;
        this.auditLogs = auditLogs;
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

    public record UserUsageRecordView(
            UUID id,
            String modality,
            String modelName,
            String provider,
            long inputTokens,
            long outputTokens,
            long costCents,
            String region,
            boolean cacheHit,
            boolean fallbackUsed,
            String requestId,
            Instant recordedAt
    ) { }

    public record UserUsagePageResponse(
            List<UserUsageRecordView> records,
            int page,
            int totalPages,
            long totalRecords
    ) { }

    @Transactional(readOnly = true)
    public UserUsagePageResponse userUsage(UUID userId, int page, int size) {
        requireUser(userId);
        int pageSize = Math.min(Math.max(1, size), 100);
        Page<UsageRecord> result = usage.findPageByUserIdOrderByRecordedAtDesc(
                userId, PageRequest.of(Math.max(0, page), pageSize));
        List<UserUsageRecordView> records = result.getContent().stream()
                .map(r -> new UserUsageRecordView(
                        r.getId(),
                        r.getModality() != null ? r.getModality().name() : "TEXT",
                        r.getModelName(),
                        r.getProvider() != null ? r.getProvider().name() : "",
                        r.getInputTokens(),
                        r.getOutputTokens(),
                        r.getCostCents(),
                        r.getRegion() != null ? r.getRegion().name() : "",
                        r.isCacheHit(),
                        r.isFallbackUsed(),
                        r.getRequestId(),
                        r.getRecordedAt()))
                .toList();
        return new UserUsagePageResponse(records, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @Transactional
    public UserView updateUserStatus(UUID userId, boolean active, String reason, String adminEmail) {
        User user = requireUser(userId);
        boolean previous = user.isActive();
        user.setActive(active);
        users.save(user);

        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", adminEmail);
        detail.put("targetUserId", userId.toString());
        detail.put("previousActive", previous);
        detail.put("newActive", active);
        detail.put("reason", reason != null ? reason.trim() : "");
        audit.record(AuditLogService.EVT_ADMIN_USER_STATUS_UPDATED, AuditOutcome.SUCCESS, userId, null, detail);

        return toView(user);
    }

    @Transactional
    public UserView updateUserCaps(UUID userId, Long dailyCapCents, Long monthlyCapCents, String adminEmail) {
        User user = requireUser(userId);
        user.setDailyCreditCapCents(dailyCapCents != null && dailyCapCents > 0 ? dailyCapCents : null);
        user.setMonthlyCreditCapCents(monthlyCapCents != null && monthlyCapCents > 0 ? monthlyCapCents : null);
        users.save(user);

        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", adminEmail);
        detail.put("targetUserId", userId.toString());
        detail.put("dailyCreditCapCents", user.getDailyCreditCapCents());
        detail.put("monthlyCreditCapCents", user.getMonthlyCreditCapCents());
        audit.record(AuditLogService.EVT_ADMIN_USER_CAPS_UPDATED, AuditOutcome.SUCCESS, userId, null, detail);

        return toView(user);
    }

    public record AdminApiKeyView(
            UUID id,
            String name,
            String keyPrefix,
            boolean active,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant createdAt
    ) { }

    @Transactional(readOnly = true)
    public List<AdminApiKeyView> userKeys(UUID userId) {
        requireUser(userId);
        return apiKeys.findByUserId(userId).stream()
                .map(k -> new AdminApiKeyView(
                        k.getId(), k.getName(), k.getKeyPrefix(), k.isActive(),
                        k.getLastUsedAt(), k.getExpiresAt(), k.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void revokeKey(UUID keyId, String adminEmail) {
        ApiKey key = apiKeys.findById(keyId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        key.setActive(false);
        apiKeys.save(key);

        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", adminEmail);
        detail.put("keyId", keyId.toString());
        detail.put("keyPrefix", key.getKeyPrefix());
        detail.put("targetUserId", key.getUserId().toString());
        audit.record(AuditLogService.EVT_ADMIN_API_KEY_REVOKED, AuditOutcome.SUCCESS, key.getUserId(), null, detail);
    }

    public record AdminJobView(
            UUID id,
            UUID userId,
            String modality,
            String provider,
            String model,
            String promptSnippet,
            String status,
            String outputUrl,
            Long estimatedCostCents,
            Long actualCostCents,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) { }

    public record AdminJobPageResponse(
            List<AdminJobView> jobs,
            int page,
            int totalPages,
            long totalJobs
    ) { }

    @Transactional(readOnly = true)
    public AdminJobPageResponse listJobs(String statusStr, String modalityStr, int page, int size) {
        JobStatus status = null;
        if (statusStr != null && !statusStr.isBlank() && !"ALL".equalsIgnoreCase(statusStr)) {
            try {
                status = JobStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { }
        }
        Modality modality = null;
        if (modalityStr != null && !modalityStr.isBlank() && !"ALL".equalsIgnoreCase(modalityStr)) {
            try {
                modality = Modality.valueOf(modalityStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { }
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<GenerationJob> result;
        if (status != null && modality != null) {
            result = jobs.findByStatusAndModalityOrderByCreatedAtDesc(status, modality, pageable);
        } else if (status != null) {
            result = jobs.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else if (modality != null) {
            result = jobs.findByModalityOrderByCreatedAtDesc(modality, pageable);
        } else {
            result = jobs.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<AdminJobView> list = result.getContent().stream().map(j -> new AdminJobView(
                j.getId(),
                j.getUserId(),
                j.getModality() != null ? j.getModality().name() : "",
                j.getProvider() != null ? j.getProvider().name() : "",
                j.getModel(),
                j.getPrompt() != null ? j.getPrompt().toString() : "",
                j.getStatus() != null ? j.getStatus().name() : "",
                j.getOutputUrl(),
                j.getEstimatedCostCents(),
                j.getActualCostCents(),
                j.getErrorMessage(),
                j.getCreatedAt(),
                j.getStartedAt(),
                j.getCompletedAt()
        )).toList();
        return new AdminJobPageResponse(list, result.getNumber(), result.getTotalPages(), result.getTotalElements());
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

    // ===== Audit Logs ==================================================

    public record AdminAuditLogView(
            UUID id,
            UUID userId,
            String eventType,
            Map<String, Object> eventDetail,
            String ipAddress,
            AuditOutcome outcome,
            Instant createdAt
    ) { }

    public record AdminAuditLogPageResponse(
            List<AdminAuditLogView> logs,
            int page,
            int totalPages,
            long totalLogs
    ) { }

    public AdminAuditLogPageResponse listAuditLogs(String eventType, int page, int size) {
        Pageable p = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        org.springframework.data.domain.Page<AuditLogEntry> result = (eventType != null && !eventType.isBlank())
                ? auditLogs.findByEventTypeOrderByCreatedAtDesc(eventType.trim(), p)
                : auditLogs.findAllByOrderByCreatedAtDesc(p);

        List<AdminAuditLogView> views = result.getContent().stream()
                .map(e -> new AdminAuditLogView(
                        e.getId(),
                        e.getUserId(),
                        e.getEventType(),
                        e.getEventDetail(),
                        e.getIpAddress(),
                        e.getOutcome(),
                        e.getCreatedAt()))
                .toList();

        return new AdminAuditLogPageResponse(views, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    // ===== Payments & Exceptions =======================================

    public record AdminPaymentTransactionView(
            UUID id,
            UUID userId,
            String userEmail,
            long deltaCents,
            String source,
            String sourceRef,
            String idempotencyKey,
            String note,
            Instant occurredAt
    ) { }

    public record AdminPaymentPageResponse(
            List<AdminPaymentTransactionView> transactions,
            int page,
            int totalPages,
            long totalTransactions
    ) { }

    public record AdminWebhookHealthResponse(
            long totalRejected,
            long totalIgnored,
            long totalAutoRechargeFailed,
            long totalDuplicate,
            List<AdminAuditLogView> recentExceptions
    ) { }

    @Transactional(readOnly = true)
    public AdminPaymentPageResponse listPayments(String sourceStr, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        CreditSource source = null;
        if (sourceStr != null && !sourceStr.isBlank() && !"ALL".equalsIgnoreCase(sourceStr)) {
            try {
                source = CreditSource.valueOf(sourceStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { }
        }
        Page<CreditLedger> result = (source != null)
                ? ledger.findBySourceOrderByOccurredAtDesc(source, pageable)
                : ledger.findAllByOrderByOccurredAtDesc(pageable);

        List<UUID> userIds = result.getContent().stream().map(CreditLedger::getUserId).distinct().toList();
        Map<UUID, String> emailMap = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        List<AdminPaymentTransactionView> views = result.getContent().stream()
                .map(e -> new AdminPaymentTransactionView(
                        e.getId(),
                        e.getUserId(),
                        emailMap.getOrDefault(e.getUserId(), ""),
                        e.getDeltaCents(),
                        e.getSource() != null ? e.getSource().name() : "",
                        e.getSourceRef(),
                        e.getIdempotencyKey(),
                        e.getNote(),
                        e.getOccurredAt()))
                .toList();
        return new AdminPaymentPageResponse(views, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @Transactional
    public AdminPaymentTransactionView reconcilePayment(UUID userId, long amountCents, String paymentRef, String note, String adminEmail) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCents must be positive");
        }
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentRef is required");
        }
        requireUser(userId);
        String key = "reconcile-" + paymentRef.trim();
        CreditLedgerService.TopUpResult topUpResult = ledgerService.topUp(
                userId, amountCents, CreditSource.STRIPE, paymentRef.trim(), key,
                note != null && !note.isBlank() ? note.trim() : "Admin manual reconcile");
        if (topUpResult == CreditLedgerService.TopUpResult.DUPLICATE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment reference already credited");
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", adminEmail);
        detail.put("targetUserId", userId.toString());
        detail.put("amountCents", amountCents);
        detail.put("paymentRef", paymentRef.trim());
        detail.put("note", note != null ? note.trim() : "");
        audit.record(AuditLogService.EVT_ADMIN_PAYMENT_RECONCILED, AuditOutcome.SUCCESS, userId, null, detail);

        CreditLedger entry = ledger.findByIdempotencyKey(key).orElseThrow();
        User user = requireUser(userId);
        return new AdminPaymentTransactionView(
                entry.getId(), user.getId(), user.getEmail(), entry.getDeltaCents(),
                entry.getSource().name(), entry.getSourceRef(), entry.getIdempotencyKey(),
                entry.getNote(), entry.getOccurredAt());
    }

    @Transactional
    public AdminPaymentTransactionView refundPayment(UUID userId, long amountCents, String paymentRef, String reason, String adminEmail) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCents must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        requireUser(userId);
        String key = "refund-" + (paymentRef != null && !paymentRef.isBlank() ? paymentRef.trim() : UUID.randomUUID());
        CreditLedger entry = ledgerService.adminRefund(
                userId, amountCents, key,
                reason.trim(), adminEmail);
        Map<String, Object> detail = new HashMap<>();
        detail.put("adminEmail", adminEmail);
        detail.put("targetUserId", userId.toString());
        detail.put("amountCents", amountCents);
        detail.put("paymentRef", paymentRef != null ? paymentRef.trim() : "");
        detail.put("reason", reason.trim());
        audit.record(AuditLogService.EVT_ADMIN_PAYMENT_REFUNDED, AuditOutcome.SUCCESS, userId, null, detail);

        User user = requireUser(userId);
        return new AdminPaymentTransactionView(
                entry.getId(), user.getId(), user.getEmail(), entry.getDeltaCents(),
                entry.getSource().name(), entry.getSourceRef(), entry.getIdempotencyKey(),
                entry.getNote(), entry.getOccurredAt());
    }

    @Transactional(readOnly = true)
    public AdminWebhookHealthResponse webhookHealth() {
        List<String> exceptionEventTypes = List.of(
                AuditLogService.EVT_BILLING_WEBHOOK_REJECTED,
                AuditLogService.EVT_BILLING_WEBHOOK_IGNORED,
                AuditLogService.EVT_BILLING_AUTORECHARGE_FAILED,
                AuditLogService.EVT_BILLING_TOPUP_DUPLICATE
        );
        Pageable p = PageRequest.of(0, 50);
        Page<AuditLogEntry> exceptionsPage = auditLogs.findByEventTypeInOrderByCreatedAtDesc(exceptionEventTypes, p);
        List<AdminAuditLogView> exceptions = exceptionsPage.getContent().stream()
                .map(e -> new AdminAuditLogView(
                        e.getId(), e.getUserId(), e.getEventType(), e.getEventDetail(),
                        e.getIpAddress(), e.getOutcome(), e.getCreatedAt()))
                .toList();
        long rejected = exceptions.stream().filter(e -> AuditLogService.EVT_BILLING_WEBHOOK_REJECTED.equals(e.eventType())).count();
        long ignored = exceptions.stream().filter(e -> AuditLogService.EVT_BILLING_WEBHOOK_IGNORED.equals(e.eventType())).count();
        long autorechargeFailed = exceptions.stream().filter(e -> AuditLogService.EVT_BILLING_AUTORECHARGE_FAILED.equals(e.eventType())).count();
        long duplicate = exceptions.stream().filter(e -> AuditLogService.EVT_BILLING_TOPUP_DUPLICATE.equals(e.eventType())).count();
        return new AdminWebhookHealthResponse(rejected, ignored, autorechargeFailed, duplicate, exceptions);
    }

    private static UUID tryParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

