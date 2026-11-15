package ai.godance.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.ImagesProperties;
import ai.godance.domain.ApiKey;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.ImageResponseMode;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.security.ApiKeyMinter;
import ai.godance.security.ApiKeyMinter.Minted;
import ai.godance.web.dto.ApiKeyDtos.ApiKeySummary;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyRequest;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.ApiKeyDtos.UpdateApiKeyRequest;
import tools.jackson.databind.JsonNode;

@Service
@EnableConfigurationProperties(ImagesProperties.class)
public class ApiKeyService {

    /** The request and response names of a key's limits; the audit detail uses them too. */
    static final String DAILY_CAP = "daily_cap_cents";
    static final String MONTHLY_CAP = "monthly_cap_cents";
    static final String EXPIRES_AT = "expires_at";

    private final ApiKeyRepository repo;
    private final ApiKeyMinter minter;
    private final AuditLogService audit;
    private final ImagesProperties images;
    private final SpendingLimitService spendingLimits;

    public ApiKeyService(ApiKeyRepository repo, ApiKeyMinter minter, AuditLogService audit,
                         ImagesProperties images, SpendingLimitService spendingLimits) {
        this.repo = repo;
        this.minter = minter;
        this.audit = audit;
        this.images = images;
        this.spendingLimits = spendingLimits;
    }

    /**
     * Mints a key for the caller, with the caps and the expiry the request
     * asks for (none of them by default). 400 when a cap is not a whole
     * number of cents of at least 1, or the expiry is not in the future.
     */
    @Transactional
    public CreateApiKeyResponse create(UUID userId, CreateApiKeyRequest req, String ip) {
        // What the request asks for, or the deployment's default for new keys.
        ImageResponseMode imageResponse = req.imageResponse() == null
                ? images.newKeyResponse()
                : requireImageResponse(req.imageResponse());
        Long dailyCap = capOf(DAILY_CAP, req.dailyCapCents());
        Long monthlyCap = capOf(MONTHLY_CAP, req.monthlyCapCents());
        Instant expiresAt = expiryOf(req.expiresAt());
        Minted minted = minter.mint();
        ApiKey k = new ApiKey();
        k.setUserId(userId);
        k.setName(req.name().trim());
        k.setKeyHash(minted.hash());
        k.setKeyPrefix(minted.prefix());
        k.setScopes(req.scopes());
        k.setImageResponse(imageResponse);
        k.setDailyCreditCapCents(dailyCap);
        k.setMonthlyCreditCapCents(monthlyCap);
        k.setExpiresAt(expiresAt);
        k.setActive(true);
        repo.save(k);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("apiKeyId", k.getId().toString());
        detail.put("name", k.getName());
        if (dailyCap != null || monthlyCap != null || expiresAt != null) {
            // A key born with limits: record them, since no api_key.caps_changed row will.
            detail.putAll(limitsOf(k));
        }
        audit.record(AuditLogService.EVT_API_KEY_CREATED, AuditOutcome.SUCCESS, userId, ip, detail);

        return new CreateApiKeyResponse(
                k.getId(), k.getName(), k.getKeyPrefix(), minted.plaintext(), k.getCreatedAt(),
                imageResponse.wire(), dailyCap, monthlyCap, expiresAt);
    }

    /**
     * Changes the settings of the caller's key that the request names, and
     * returns the key: how its image requests answer ({@code image_response}),
     * its daily and monthly caps, and its expiry. A field left out is left
     * as it is; a cap or the expiry sent as null is cleared.
     *
     * <p>400 when the request names none of them or a value is not valid,
     * and then nothing is changed; 404 for an unknown key or another user's.
     * The caps are enforced by {@link SpendingLimitService}, and the expiry
     * by the API-key filter: this only stores them.
     */
    @Transactional
    public ApiKeySummary update(UUID userId, UUID keyId, UpdateApiKeyRequest req, String ip) {
        if (req == null || (req.imageResponse() == null && req.dailyCapCents() == null
                && req.monthlyCapCents() == null && req.expiresAt() == null)) {
            throw badRequest("nothing to change: send image_response, " + DAILY_CAP + ", "
                    + MONTHLY_CAP + " or " + EXPIRES_AT);
        }
        // Every value is checked before anything is read or written.
        ImageResponseMode imageResponse = req.imageResponse() == null ? null
                : requireImageResponse(req.imageResponse().isString() ? req.imageResponse().asString() : null);
        Long dailyCap = capOf(DAILY_CAP, req.dailyCapCents());
        Long monthlyCap = capOf(MONTHLY_CAP, req.monthlyCapCents());
        Instant expiresAt = expiryOf(req.expiresAt());

        ApiKey k = repo.findById(keyId)
                // Don't leak existence — same 404 as not-found.
                .filter(key -> key.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found"));

        boolean changed = false;
        if (imageResponse != null && k.getImageResponse() != imageResponse) {
            k.setImageResponse(imageResponse);
            changed = true;
            audit.record(AuditLogService.EVT_API_KEY_UPDATED, AuditOutcome.SUCCESS, userId, ip,
                    Map.of("apiKeyId", keyId.toString(), "name", k.getName(),
                            "field", "image_response", "value", imageResponse.wire()));
        }

        Map<String, Object> before = limitsOf(k);
        if (req.dailyCapCents() != null && !Objects.equals(k.getDailyCreditCapCents(), dailyCap)) {
            k.setDailyCreditCapCents(dailyCap);
            // A new cap has a new 80% mark: let its warning be sent again in this period.
            k.setDailyCapWarnedAt(null);
        }
        if (req.monthlyCapCents() != null && !Objects.equals(k.getMonthlyCreditCapCents(), monthlyCap)) {
            k.setMonthlyCreditCapCents(monthlyCap);
            k.setMonthlyCapWarnedAt(null);
        }
        if (req.expiresAt() != null) {
            k.setExpiresAt(expiresAt);
        }
        Map<String, Object> after = limitsOf(k);
        if (!before.equals(after)) {
            changed = true;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("apiKeyId", keyId.toString());
            detail.put("name", k.getName());
            detail.put("old", before);
            detail.put("new", after);
            audit.record(AuditLogService.EVT_API_KEY_CAPS_CHANGED, AuditOutcome.SUCCESS, userId, ip, detail);
        }
        if (changed) {
            repo.save(k);
        }
        return summaryOf(k);
    }

    /**
     * How image requests made with this key answer. A key that no longer
     * exists answers as every key did before the setting: with a job.
     */
    @Transactional(readOnly = true)
    public ImageResponseMode imageResponseOf(UUID keyId) {
        return repo.findById(keyId).map(ApiKey::getImageResponse).orElse(ImageResponseMode.ASYNC);
    }

    private static ImageResponseMode requireImageResponse(String value) {
        ImageResponseMode mode = ImageResponseMode.fromWire(value);
        if (mode == null) {
            throw badRequest("image_response must be \"async\" or \"sync\"");
        }
        return mode;
    }

    /**
     * A cap from a request, in cents: null when the field was left out or
     * sent as null (no cap), else a whole number of at least 1. Anything
     * else is a 400: a fraction, a string, zero or less. Zero is refused
     * rather than read as "no spending", because the enforcement treats a
     * cap of zero as no cap at all.
     */
    private static Long capOf(String field, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw badRequest(field + " must be a whole number of cents, at least 1, or null for no cap");
        }
        return value.longValue();
    }

    /**
     * An expiry from a request: null when the field was left out or sent as
     * null (the key does not expire), else an ISO-8601 date and time that
     * is still in the future, kept to the second.
     */
    private static Instant expiryOf(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Instant at = null;
        if (value.isString()) {
            try {
                at = Instant.parse(value.asString().trim()).truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException notADateTime) {
                // Falls through to the 400 below.
            }
        }
        if (at == null) {
            throw badRequest(EXPIRES_AT + " must be an ISO-8601 date and time with a zone, "
                    + "such as 2027-01-31T00:00:00Z, or null for no expiry");
        }
        if (!at.isAfter(Instant.now())) {
            throw badRequest(EXPIRES_AT + " must be in the future");
        }
        return at;
    }

    /** A key's two caps and its expiry under their request names, nulls included: what the audit log keeps. */
    private static Map<String, Object> limitsOf(ApiKey k) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put(DAILY_CAP, k.getDailyCreditCapCents());
        limits.put(MONTHLY_CAP, k.getMonthlyCreditCapCents());
        limits.put(EXPIRES_AT, k.getExpiresAt() == null ? null : k.getExpiresAt().toString());
        return limits;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    /** The key as its owner sees it, with what it has spent today and this month. */
    private ApiKeySummary summaryOf(ApiKey k) {
        return ApiKeySummary.from(k, spendingLimits.spendOf(k.getId()));
    }

    @Transactional(readOnly = true)
    public List<ApiKeySummary> listForUser(UUID userId) {
        return repo.findByUserId(userId).stream().map(this::summaryOf).toList();
    }

    @Transactional
    public void delete(UUID userId, UUID keyId, String ip) {
        ApiKey k = repo.findById(keyId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found"));
        if (!k.getUserId().equals(userId)) {
            // Don't leak existence — same 404 as not-found.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "api key not found");
        }
        repo.delete(k);
        audit.record(AuditLogService.EVT_API_KEY_DELETED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("apiKeyId", keyId.toString(), "name", k.getName()));
    }
}
