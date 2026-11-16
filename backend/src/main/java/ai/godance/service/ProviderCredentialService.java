package ai.godance.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.ByokProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.domain.Provider;
import ai.godance.domain.ProviderCredential;
import ai.godance.gateway.CallerKey;
import ai.godance.gateway.ImageProviderGateway;
import ai.godance.gateway.ProviderGateway;
import ai.godance.gateway.ProviderKeyCheck;
import ai.godance.repository.ProviderCredentialRepository;
import ai.godance.security.KeyVault;
import ai.godance.web.dto.ByokDtos.CredentialRequest;
import ai.godance.web.dto.ByokDtos.CredentialView;

/**
 * A user's own provider keys (round 14 brief 03), and the decision, for
 * one request, whether it goes out on one.
 *
 * <p><b>Keys.</b> A key is checked with the provider before it is stored,
 * stored only through {@link KeyVault}, and never returned, logged or
 * audited: {@code last4} is all of it that leaves this class in clear,
 * except to the provider the key belongs to.
 *
 * <p><b>The region decides first.</b> A key for a provider the current
 * region forbids is refused before it is sent anywhere, and a request's key
 * is chosen only after {@link ProviderGateway#dispatch} has passed it.
 *
 * <p><b>When a request uses one.</b> The provider is offered
 * ({@code godance.byok.providers}), the adapter that will serve the request
 * takes caller keys, and the user's key for that provider was valid when
 * last checked. Otherwise the request goes out on the platform's key at the
 * platform price, exactly as without this feature.
 */
@Service
@EnableConfigurationProperties(ByokProperties.class)
public class ProviderCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialService.class);

    static final String AUDIT_SAVED = "byok.saved";
    static final String AUDIT_REMOVED = "byok.removed";
    static final String AUDIT_REJECTED = "byok.rejected";

    /** A key is at most this long; real ones are far shorter. */
    static final int KEY_MAX = 512;

    private final ProviderCredentialRepository credentials;
    private final KeyVault vault;
    private final ProviderKeyCheck keyCheck;
    private final ProviderGateway chatGateway;
    private final ImageProviderGateway imageGateway;
    private final AuditLogService audit;
    private final ByokProperties props;

    public ProviderCredentialService(ProviderCredentialRepository credentials, KeyVault vault,
                                     ProviderKeyCheck keyCheck, ProviderGateway chatGateway,
                                     ImageProviderGateway imageGateway, AuditLogService audit, ByokProperties props) {
        this.credentials = credentials;
        this.vault = vault;
        this.keyCheck = keyCheck;
        this.chatGateway = chatGateway;
        this.imageGateway = imageGateway;
        this.audit = audit;
        this.props = props;
        for (Provider provider : props.providers()) {
            if (!keyCheck.checkable().contains(provider)) {
                throw new IllegalStateException("godance.byok.providers lists " + provider
                        + ", which has no key check; providers with one: " + keyCheck.checkable());
            }
        }
    }

    // ===== The routes ===================================================

    /**
     * Every provider a key may be stored for, and every key the user holds,
     * by provider name: never a key, only its last four characters.
     */
    @Transactional(readOnly = true)
    public List<CredentialView> list(UUID userId) {
        Map<Provider, ProviderCredential> held = new LinkedHashMap<>();
        for (ProviderCredential c : credentials.findByUserIdOrderByProviderAsc(userId)) held.put(c.getProvider(), c);
        List<CredentialView> views = new ArrayList<>();
        for (Provider provider : props.providers()) {
            ProviderCredential c = held.remove(provider);
            views.add(c == null
                    ? new CredentialView(provider.name(), null, "none", false, usedFor(provider), null, props.feeBasisPoints())
                    : view(c));
        }
        for (ProviderCredential c : held.values()) views.add(view(c));
        return views;
    }

    /**
     * Stores the user's key for {@code provider}, after the provider
     * accepted it, in place of any key held before. Without {@code api_key},
     * changes only {@code fallback_to_platform} of the key already held.
     *
     * @throws ByokRefusedException 400 {@code byok_provider_not_supported},
     *         {@code invalid_api_key} or {@code byok_key_rejected}; 404
     *         {@code byok_credential_not_found}; 502 {@code byok_check_failed}
     * @throws ResponseStatusException 503 when the region forbids the provider
     */
    @Transactional
    public CredentialView put(UUID userId, String providerName, CredentialRequest req, String ip) {
        Provider provider = supported(providerName);
        boolean fallback = req != null && Boolean.TRUE.equals(req.fallbackToPlatform());
        String key = req == null || req.apiKey() == null ? null : req.apiKey().strip();
        if (key == null || key.isEmpty()) {
            if (req != null && req.apiKey() != null) throw ByokRefusedException.invalidKey("api_key must not be blank");
            // Only the fallback setting changes; the key stays as it was checked.
            ProviderCredential held = credentials.findByUserIdAndProvider(userId, provider)
                    .orElseThrow(() -> ByokRefusedException.notFound(provider));
            held.setFallbackToPlatform(fallback);
            credentials.save(held);
            return view(held);
        }
        if (key.length() > KEY_MAX || key.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            throw ByokRefusedException.invalidKey("api_key is one token of at most " + KEY_MAX + " characters");
        }
        // The region decides first: a key for a forbidden provider is not even checked.
        chatGateway.requireAllowed(provider);

        ProviderKeyCheck.Verdict verdict = keyCheck.check(provider, key, props.checkTimeout());
        String last4 = CallerKey.last4(key);
        if (verdict == ProviderKeyCheck.Verdict.REJECTED) {
            // Its own transaction: the refusal below rolls this one back.
            audit.recordInNewTransaction(AUDIT_REJECTED, AuditOutcome.FAILURE, userId, ip,
                    Map.of("provider", provider.name(), "last4", last4, "when", "save"));
            throw ByokRefusedException.keyRejected(provider);
        }
        if (verdict != ProviderKeyCheck.Verdict.VALID) {
            throw ByokRefusedException.checkFailed(provider);
        }

        Instant now = Instant.now();
        ProviderCredential c = credentials.findByUserIdAndProvider(userId, provider).orElseGet(ProviderCredential::new);
        c.setUserId(userId);
        c.setProvider(provider);
        c.setKeyEncrypted(vault.encrypt(key.getBytes(StandardCharsets.UTF_8), context(userId, provider)));
        c.setLast4(last4);
        c.setStatus(ProviderCredential.VALID);
        c.setFallbackToPlatform(fallback);
        c.setCheckedAt(now);
        credentials.save(c);
        audit.record(AUDIT_SAVED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("provider", provider.name(), "last4", last4, "fallbackToPlatform", fallback));
        return view(c);
    }

    /**
     * Deletes the user's key for {@code provider}. Its requests go out on the
     * platform's key from now on.
     *
     * @throws ByokRefusedException 404 {@code byok_credential_not_found}
     */
    @Transactional
    public void delete(UUID userId, String providerName, String ip) {
        Provider provider = parse(providerName);
        if (credentials.deleteByUserIdAndProvider(userId, provider.name()) == 0) {
            throw ByokRefusedException.notFound(provider);
        }
        audit.record(AUDIT_REMOVED, AuditOutcome.SUCCESS, userId, ip, Map.of("provider", provider.name()));
    }

    // ===== For one request ==============================================

    /**
     * Whether a request to {@code model} by this user is priced as one on
     * their own key: the provider is offered, the adapter that serves this
     * modality takes caller keys, and the user's key was valid when last
     * checked. Reads no key and sends nothing.
     */
    @Transactional(readOnly = true)
    public boolean applies(UUID userId, ModelInfo model) {
        if (userId == null || !props.supports(model.provider())) return false;
        if (!takesCallerKeys(model)) return false;
        return credentials.hasValid(userId, model.provider().name());
    }

    /**
     * The user's key for {@code model}'s provider, decrypted, for one call
     * that has passed {@link ProviderGateway#dispatch}; empty when
     * {@link #applies} says no, or the key can no longer be read.
     */
    @Transactional(readOnly = true)
    public Optional<UsableKey> keyFor(UUID userId, ModelInfo model) {
        if (!applies(userId, model)) return Optional.empty();
        return credentials.findByUserIdAndProvider(userId, model.provider())
                .filter(c -> ProviderCredential.VALID.equals(c.getStatus()))
                .flatMap(c -> {
                    try {
                        String key = new String(vault.decrypt(c.getKeyEncrypted(), context(userId, c.getProvider())),
                                StandardCharsets.UTF_8);
                        return Optional.of(new UsableKey(c.getId(), userId, CallerKey.of(c.getProvider(), key),
                                c.isFallbackToPlatform()));
                    } catch (IllegalStateException e) {
                        log.warn("byok: the {} key of user {} could not be decrypted; the platform's key is used",
                                c.getProvider(), userId);
                        return Optional.empty();
                    }
                });
    }

    /** Whether the user's key for this provider may be replaced by the platform's when refused. */
    @Transactional(readOnly = true)
    public boolean fallsBackToPlatform(UUID userId, Provider provider) {
        return credentials.findByUserIdAndProvider(userId, provider)
                .map(ProviderCredential::isFallbackToPlatform).orElse(false);
    }

    /**
     * The provider refused the user's key (401 or 403): the key is marked
     * invalid, so no later request is priced or sent as one on it.
     */
    @Transactional
    public void rejected(UsableKey key, String ip) {
        if (credentials.markInvalid(key.credentialId(), Instant.now()) > 0) {
            audit.record(AUDIT_REJECTED, AuditOutcome.FAILURE, key.userId(), ip, Map.of(
                    "provider", key.key().provider().name(), "last4", key.key().last4(), "when", "use"));
        }
    }

    /** Closing an account deletes every key it holds. */
    @Transactional
    public int deleteAllOf(UUID userId) {
        return credentials.deleteByUserId(userId);
    }

    public int feeBasisPoints() {
        return props.feeBasisPoints();
    }

    /** A user's key, decrypted for one call: what {@link CallerKey} carries, and the stored row it came from. */
    public record UsableKey(UUID credentialId, UUID userId, CallerKey key, boolean fallbackToPlatform) {
        @Override
        public String toString() {
            return "UsableKey[" + key + "]";
        }
    }

    // ===== Helpers ======================================================

    /** KeyVault's context for a key: the user and the provider, so a value copied to another row does not decrypt. */
    static String context(UUID userId, Provider provider) {
        return "byok:" + userId + ":" + provider.name();
    }

    private boolean takesCallerKeys(ModelInfo model) {
        return switch (model.modality()) {
            case TEXT -> chatGateway.takesCallerKeys(model.provider());
            case IMAGE -> imageGateway.takesCallerKeys(model.provider());
            default -> false;
        };
    }

    private List<String> usedFor(Provider provider) {
        List<String> used = new ArrayList<>();
        if (chatGateway.takesCallerKeys(provider)) used.add("chat");
        if (imageGateway.takesCallerKeys(provider)) used.add("images");
        return used;
    }

    private CredentialView view(ProviderCredential c) {
        List<String> used = usedFor(c.getProvider());
        String status = !props.supports(c.getProvider()) ? "not_supported"
                : used.isEmpty() ? "unsupported_adapter"
                : c.getStatus();
        return new CredentialView(c.getProvider().name(), c.getLast4(), status, c.isFallbackToPlatform(), used,
                c.getCheckedAt(), props.feeBasisPoints());
    }

    private Provider supported(String name) {
        Provider provider = parse(name);
        if (!props.supports(provider)) throw ByokRefusedException.notSupported(name);
        return provider;
    }

    private static Provider parse(String name) {
        if (name == null) throw ByokRefusedException.notSupported(null);
        try {
            return Provider.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ByokRefusedException.notSupported(name);
        }
    }
}
