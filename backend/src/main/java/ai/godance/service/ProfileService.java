package ai.godance.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.config.CommunityProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.Profile;
import ai.godance.domain.PublishedStatus;
import ai.godance.domain.User;
import ai.godance.repository.FollowRepository;
import ai.godance.repository.ProfileRepository;
import ai.godance.repository.PublishedRecipeRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.CommunityDtos.ProfileRequest;
import ai.godance.web.dto.CommunityDtos.ProfileView;

/**
 * Profiles and follows (round 14 brief 01). A profile is an account's public
 * name: only an adult account with a verified email may set one, and what a
 * public route shows of it is what a moderator approved. Follows are by
 * handle; who follows whom is never shown, only how many.
 */
@Service
@EnableConfigurationProperties(CommunityProperties.class)
public class ProfileService {

    /** 3–30 characters: lowercase letters, digits and hyphens, starting and ending with a letter or digit. */
    static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$");
    static final int DISPLAY_NAME_MAX = 60;
    static final int LINK_MAX = 300;

    /**
     * Handles nobody may take: names of the site's own pages and roles, and
     * words that would pass for staff. Checked after lowercasing; a handle
     * that only adds hyphens or digits to one of these is refused too.
     */
    static final Set<String> RESERVED = Set.of(
            "about", "account", "admin", "administrator", "api", "app", "billing", "blog", "community",
            "compare", "create", "dashboard", "docs", "edit", "email", "explore", "feed", "follow",
            "followers", "following", "gallery", "godance", "help", "history", "home", "jobs", "keys",
            "legal", "login", "logout", "mail", "me", "mod", "moderator", "moderators", "new", "null",
            "official", "privacy", "profile", "profiles", "public", "recipe", "recipes", "referral",
            "report", "reports", "root", "search", "security", "settings", "signin", "signup", "staff",
            "status", "studio", "support", "system", "team", "terms", "undefined", "user", "users", "www");

    /** Why an account may not publish, as {@code cannot_publish} names it. */
    static final String NOT_VERIFIED = "email_not_verified";
    static final String NOT_ADULT = "not_adult";
    static final String NO_PROFILE = "no_profile";

    private final ProfileRepository profiles;
    private final FollowRepository follows;
    private final UserRepository users;
    private final PublishedRecipeRepository published;
    private final AuditLogService audit;
    private final CommunityProperties props;

    public ProfileService(ProfileRepository profiles, FollowRepository follows, UserRepository users,
                          PublishedRecipeRepository published, AuditLogService audit, CommunityProperties props) {
        this.profiles = profiles;
        this.follows = follows;
        this.users = users;
        this.published = published;
        this.audit = audit;
        this.props = props;
    }

    // ===== The caller's profile =========================================

    /** The caller's profile, or the empty one, and whether they may publish. */
    @Transactional(readOnly = true)
    public ProfileView get(UUID userId) {
        requireEnabled();
        User user = users.findById(userId).orElseThrow(() -> CommunityRefusedException.notFound("account"));
        return view(user, profiles.findById(userId).orElse(null));
    }

    /**
     * Sets the caller's profile. The handle cannot change once a moderator
     * approved the profile; a new display name or link then waits for review,
     * and the public routes keep showing the approved ones.
     *
     * @throws CommunityRefusedException 403 {@code community_not_allowed};
     *         400 {@code invalid_profile}; 409 {@code handle_taken} or
     *         {@code handle_locked}
     */
    @Transactional
    public ProfileView put(UUID userId, ProfileRequest req, String ip) {
        requireEnabled();
        User user = users.findById(userId).orElseThrow(() -> CommunityRefusedException.notFound("account"));
        requireMayTakePart(user);
        if (req == null) throw invalid("a profile needs a handle and a display name");
        String handle = handle(req.handle());
        String displayName = displayName(req.displayName());
        String link = link(req.link());

        Profile profile = profiles.findById(userId).orElse(null);
        boolean created = profile == null;
        if (created) {
            profile = new Profile();
            profile.setUserId(userId);
        } else if (profile.isApproved() && !profile.getHandle().equals(handle)) {
            throw CommunityRefusedException.of(HttpStatus.CONFLICT, CommunityRefusedException.CODE_HANDLE_LOCKED,
                    "your handle is fixed once a moderator has approved your profile");
        }
        profiles.findByHandle(handle).filter(other -> !other.getUserId().equals(userId)).ifPresent(other -> {
            throw handleTaken();
        });
        boolean changed = created || !Objects.equals(profile.getDisplayName(), displayName)
                || !Objects.equals(profile.getLink(), link) || !Objects.equals(profile.getHandle(), handle);
        profile.setHandle(handle);
        profile.setDisplayName(displayName);
        profile.setLink(link);
        // A new change is a new request for review: a refusal of the last one no longer applies.
        if (changed) profile.setReviewReason(null);
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            // Another account took the handle between the check and the write.
            throw handleTaken();
        }
        if (changed) {
            audit.record(created ? "community.profile_created" : "community.profile_changed", AuditOutcome.SUCCESS,
                    userId, ip, Map.of("handle", handle));
        }
        return view(user, profile);
    }

    // ===== Follows ======================================================

    /**
     * Follows the account with this public profile. Following one already
     * followed changes nothing.
     *
     * @throws CommunityRefusedException 404 {@code not_found} for a handle
     *         with no public profile; 400 {@code follow_self}
     */
    @Transactional
    public void follow(UUID userId, String handle) {
        requireEnabled();
        Profile followee = publicProfile(handle);
        if (followee.getUserId().equals(userId)) {
            throw CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_FOLLOW_SELF,
                    "you cannot follow yourself");
        }
        follows.follow(userId, followee.getUserId());
    }

    /** Stops following; not following already changes nothing. */
    @Transactional
    public void unfollow(UUID userId, String handle) {
        requireEnabled();
        String normalised = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        profiles.findByHandle(normalised).ifPresent(p -> follows.unfollow(userId, p.getUserId()));
    }

    /** The handles the caller follows, most recently followed first. Seen by the caller alone. */
    @Transactional(readOnly = true)
    public List<String> following(UUID userId) {
        requireEnabled();
        return follows.followedHandles(userId);
    }

    // ===== For the other community services =============================

    /**
     * The profile with this handle when it is public: approved by a
     * moderator, and its owner has a public recipe.
     *
     * @throws CommunityRefusedException 404 {@code not_found}
     */
    Profile publicProfile(String handle) {
        String normalised = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        return profiles.findByHandle(normalised)
                .filter(Profile::isApproved)
                .filter(p -> published.countByUserIdAndStatusIn(p.getUserId(), List.of(PublishedStatus.PUBLIC)) > 0)
                .orElseThrow(() -> CommunityRefusedException.notFound("profile"));
    }

    /** Why the account may not set a profile or publish, or null when it may; a profile is not needed here. */
    static String cannotTakePart(User user) {
        if (user.getAgeStatus() != AgeStatus.ADULT) return NOT_ADULT;
        if (!user.isEmailVerified()) return NOT_VERIFIED;
        return null;
    }

    /** @throws CommunityRefusedException 403 {@code community_not_allowed} for an account that may not take part */
    static void requireMayTakePart(User user) {
        String why = cannotTakePart(user);
        if (why == null) return;
        CommunityRefusedException e = CommunityRefusedException.of(HttpStatus.FORBIDDEN,
                CommunityRefusedException.CODE_NOT_ALLOWED, NOT_ADULT.equals(why)
                        ? "only adult accounts can have a public profile or publish recipes"
                        : "verify your email address to have a public profile or publish recipes");
        e.getBody().setProperty("reason", why);
        throw e;
    }

    void requireEnabled() {
        if (!props.enabled()) throw CommunityRefusedException.disabled();
    }

    // ===== Validation ===================================================

    static String handle(String raw) {
        if (raw == null || raw.isBlank()) throw invalid("a profile needs a handle");
        String handle = raw.trim().toLowerCase(Locale.ROOT);
        if (handle.length() < 3 || handle.length() > 30) {
            throw invalid("a handle has 3 to 30 characters");
        }
        if (!HANDLE.matcher(handle).matches()) {
            throw invalid("a handle uses lowercase letters, digits and hyphens, and starts and ends with a letter or digit");
        }
        if (handle.contains("--")) throw invalid("a handle has no two hyphens in a row");
        if (isReserved(handle)) {
            CommunityRefusedException e = CommunityRefusedException.of(HttpStatus.BAD_REQUEST,
                    CommunityRefusedException.CODE_INVALID_PROFILE, "that handle is reserved");
            e.getBody().setProperty("reason", "reserved");
            throw e;
        }
        return handle;
    }

    /** A reserved word, or one with only hyphens and digits added around it. */
    static boolean isReserved(String handle) {
        if (RESERVED.contains(handle)) return true;
        String letters = handle.replaceAll("[0-9-]", "");
        return RESERVED.contains(letters) || letters.startsWith("godance") || letters.startsWith("admin");
    }

    static String displayName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) throw invalid("a profile needs a display name");
        if (name.codePointCount(0, name.length()) > DISPLAY_NAME_MAX) {
            throw invalid("a display name has at most " + DISPLAY_NAME_MAX + " characters");
        }
        if (name.chars().anyMatch(Character::isISOControl)) throw invalid("a display name is one line of text");
        return name;
    }

    static String link(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String link = raw.trim();
        if (link.length() > LINK_MAX) throw invalid("a link has at most " + LINK_MAX + " characters");
        try {
            URI uri = new URI(link);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null) {
                throw invalid("a link is an https:// address");
            }
        } catch (URISyntaxException e) {
            throw invalid("a link is an https:// address");
        }
        if (!link.regionMatches(true, 0, "https://", 0, 8)) throw invalid("a link is an https:// address");
        return "https://" + link.substring(8);
    }

    private static CommunityRefusedException invalid(String detail) {
        return CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_INVALID_PROFILE, detail);
    }

    private static CommunityRefusedException handleTaken() {
        return CommunityRefusedException.of(HttpStatus.CONFLICT, CommunityRefusedException.CODE_HANDLE_TAKEN,
                "that handle belongs to another account");
    }

    private ProfileView view(User user, Profile profile) {
        String why = cannotTakePart(user);
        if (why == null && profile == null) why = NO_PROFILE;
        if (profile == null) {
            return new ProfileView(null, null, null, false, false, null, 0, why, props.licences());
        }
        return new ProfileView(profile.getHandle(), profile.getDisplayName(), profile.getLink(),
                profile.isApproved(), profile.hasChangeAwaitingReview(), profile.getReviewReason(),
                follows.countByFolloweeId(user.getId()), why, props.licences());
    }
}
