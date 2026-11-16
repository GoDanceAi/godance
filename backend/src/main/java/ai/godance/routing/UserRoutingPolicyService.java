package ai.godance.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.Modality;
import ai.godance.repository.UserRepository;
import ai.godance.routing.RoutingPolicyDocuments.PolicyBody;
import ai.godance.routing.RoutingPolicyDocuments.PolicyList;
import ai.godance.routing.RoutingPolicyDocuments.PolicyView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Users' own routing policies (round 4's Phase 3, design §6): kept per
 * account, validated before they are stored, and read from the database when
 * a request names one, so every instance sees a change at once (the design's
 * in-memory {@code registry.upsert} would leave other instances stale). The
 * built-in presets stay in {@link RoutingPolicyRegistry} and cannot be
 * changed here.
 *
 * <p>Nothing here does anything while {@code godance.routing.user-policies}
 * is off: the routes answer 404 {@code routing_policies_disabled}, a request
 * naming a policy finds none, and no batch gets fallbacks.
 */
@Service
@EnableConfigurationProperties(UserRoutingPolicyProperties.class)
public class UserRoutingPolicyService implements UserPolicySource {

    private static final Logger log = LoggerFactory.getLogger(UserRoutingPolicyService.class);

    private final UserRoutingPolicyProperties props;
    private final RoutingPolicyRepository policies;
    private final RoutingPolicyValidator validator;
    private final RoutingPolicyRegistry presets;
    private final UserRepository users;
    private final ObjectMapper json;

    public UserRoutingPolicyService(UserRoutingPolicyProperties props, RoutingPolicyRepository policies,
                                    RoutingPolicyValidator validator, RoutingPolicyRegistry presets,
                                    UserRepository users, ObjectMapper json) {
        this.props = props;
        this.policies = policies;
        this.validator = validator;
        this.presets = presets;
        this.users = users;
        this.json = json;
    }

    /** A stored policy as its owner wrote it, and whether this write created it. */
    public record Saved(PolicyView policy, boolean created) { }

    // ===== Who may =====================================================

    /** Whether this account may write and use policies now. */
    public boolean usable(UUID userId) {
        if (!props.enabled() || userId == null) return false;
        if (props.allowList().isEmpty()) return true;
        return users.findById(userId).map(u -> props.allows(u.getEmail())).orElse(false);
    }

    /** 404 while the switch is off, 403 for an account not on the allow-list. */
    public void requireAccess(UUID userId) {
        if (!props.enabled()) throw RoutingPolicyRefusedException.disabled();
        if (!usable(userId)) throw RoutingPolicyRefusedException.notAllowed();
    }

    // ===== The API =====================================================

    /** The built-in presets, then the caller's own policies by name. */
    @Transactional(readOnly = true)
    public PolicyList list(UUID userId) {
        requireAccess(userId);
        List<PolicyView> out = new ArrayList<>();
        presets.all().stream()
                .sorted(Comparator.comparing(RoutingPolicy::id))
                .map(RoutingPolicyDocuments::presetView)
                .forEach(out::add);
        for (RoutingPolicyEntity e : policies.findByUserIdOrderByNameAsc(userId)) {
            out.add(view(e));
        }
        return new PolicyList(out);
    }

    /** One policy by the name its owner calls it: one of theirs, or a preset. */
    @Transactional(readOnly = true)
    public PolicyView get(UUID userId, String name) {
        requireAccess(userId);
        Optional<RoutingPolicy> preset = presets.find(name);
        if (preset.isPresent()) return RoutingPolicyDocuments.presetView(preset.get());
        return policies.findById(RoutingPolicyEntity.idFor(userId, name)).map(this::view)
                .orElseThrow(() -> RoutingPolicyRefusedException.notFound(name));
    }

    /** Creates or replaces the caller's policy {@code name}, once it passes every check. */
    @Transactional
    public Saved put(UUID userId, String name, PolicyBody body) {
        requireAccess(userId);
        String id = RoutingPolicyEntity.idFor(userId, name);
        Optional<RoutingPolicyEntity> existing = policies.findById(id);

        Map<String, String> otherFallbacks = new LinkedHashMap<>();
        for (RoutingPolicyEntity other : policies.findByUserIdOrderByNameAsc(userId)) {
            if (other.getId().equals(id)) continue;
            for (MediaFallback f : RoutingPolicyDocuments.toFallbacks(read(other))) {
                otherFallbacks.putIfAbsent(f.primary(), other.getName());
            }
        }
        List<String> problems = validator.problems(name, body, otherFallbacks);
        if (!problems.isEmpty()) throw RoutingPolicyRefusedException.invalid(problems);

        if (existing.isEmpty() && policies.countByUserId(userId) >= props.maxPerUser()) {
            throw RoutingPolicyRefusedException.limitReached(props.maxPerUser());
        }
        PolicyBody normalized = RoutingPolicyDocuments.normalized(body);
        RoutingPolicyEntity e = existing.orElseGet(RoutingPolicyEntity::new);
        if (existing.isEmpty()) {
            e.setId(id);
            e.setUserId(userId);
            e.setName(name);
        }
        e.setModality(Modality.TEXT);
        e.setEnabled(Boolean.TRUE.equals(normalized.enabled()));
        e.setPolicyJson(json.writeValueAsString(normalized));
        RoutingPolicyEntity saved = policies.saveAndFlush(e);
        log.info("Routing policy {} {} for user {}", name, existing.isEmpty() ? "created" : "replaced", userId);
        return new Saved(view(saved), existing.isEmpty());
    }

    /** Deletes the caller's policy {@code name}; a preset is not theirs to delete. */
    @Transactional
    public void delete(UUID userId, String name) {
        requireAccess(userId);
        String id = RoutingPolicyEntity.idFor(userId, name);
        if (!policies.existsById(id)) throw RoutingPolicyRefusedException.notFound(name);
        policies.deleteById(id);
        log.info("Routing policy {} deleted for user {}", name, userId);
    }

    // ===== For requests and batches ====================================

    /**
     * The policy a request by {@code userId} names as its model, when that
     * account may use policies and the policy is on. Usage records name it by
     * its stored id, {@code usr_<user>:<name>}.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<RoutingPolicy> find(UUID userId, String name) {
        if (!props.enabled() || userId == null || name == null
                || !RoutingPolicyValidator.NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Optional<RoutingPolicyEntity> e = policies.findById(RoutingPolicyEntity.idFor(userId, name));
        if (e.isEmpty() || !e.get().isEnabled() || !usable(userId)) return Optional.empty();
        return Optional.of(RoutingPolicyDocuments.toPolicy(e.get().getId(), read(e.get())));
    }

    /**
     * The fallbacks for {@code primary} in the account's enabled policies, for
     * a batch that opted in. A primary has fallbacks in at most one policy of
     * an account (the validator sees to it).
     */
    @Transactional(readOnly = true)
    public Optional<MediaFallback> fallbackFor(UUID userId, String primary) {
        if (!usable(userId)) return Optional.empty();
        for (RoutingPolicyEntity e : policies.findByUserIdOrderByNameAsc(userId)) {
            if (!e.isEnabled()) continue;
            for (MediaFallback f : RoutingPolicyDocuments.toFallbacks(read(e))) {
                if (f.primary().equals(primary)) return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    // ===== Helpers =====================================================

    private PolicyBody read(RoutingPolicyEntity e) {
        try {
            return json.readValue(e.getPolicyJson(), PolicyBody.class);
        } catch (JacksonException ex) {
            // Only validated, normalized JSON is ever stored; this is corruption.
            throw new IllegalStateException("routing policy " + e.getId() + " is not readable", ex);
        }
    }

    private PolicyView view(RoutingPolicyEntity e) {
        PolicyBody b = read(e);
        return new PolicyView(e.getName(), false, e.isEnabled(), b.modality(), b.rules(), b.defaultModel(),
                b.limits(), b.fallbacks(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
