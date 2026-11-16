package ai.godance.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Published recipes, profiles, follows and reports (round 14 brief 01).
 * Everything ships off: with {@code enabled} false no profile can be set,
 * nothing can be published, followed or reported, no published recipe can be
 * run, and every public route answers 404 {@code community_disabled}. The
 * moderation routes stay open to staff either way.
 *
 * @param enabled               the switch
 * @param licences              the licences an author may choose, by SPDX id
 * @param maxPendingPerDay      submissions one account may make in a day
 *                              (any status); past it, 429
 *                              {@code publish_limit_reached}
 * @param maxPublishedPerUser   recipes one account may have pending or public
 *                              at once; past it, 409 {@code publish_limit_reached}
 * @param reportLimitPerHour    reports one account may make in an hour
 * @param mediaBaseUrl          where approved example copies are served from,
 *                              when the operator serves the bucket's
 *                              {@code community/} prefix publicly (a CDN or a
 *                              public bucket); each copy's URL is this plus its
 *                              key below that prefix. Empty: copies are served
 *                              by {@code /v1/public/recipes/{id}/examples/{n}},
 *                              which redirects to a signed link
 * @param publicCacheTtl        the {@code max-age} of the public routes
 */
@ConfigurationProperties(prefix = "godance.community")
public record CommunityProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"CC-BY-4.0", "CC0-1.0"}) List<String> licences,
        @DefaultValue("5") int maxPendingPerDay,
        @DefaultValue("50") int maxPublishedPerUser,
        @DefaultValue("10") int reportLimitPerHour,
        @DefaultValue("") String mediaBaseUrl,
        @DefaultValue("5m") Duration publicCacheTtl
) {
    private static final String PREFIX = "godance.community.";
    private static final Pattern SPDX = Pattern.compile("^[A-Za-z0-9.+-]{1,32}$");

    public CommunityProperties {
        licences = licences == null ? List.of() : licences.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (licences.isEmpty()) {
            throw new IllegalStateException(PREFIX + "licences must name at least one licence");
        }
        for (String licence : licences) {
            if (!SPDX.matcher(licence).matches()) {
                throw new IllegalStateException(PREFIX + "licences must be SPDX ids of at most 32 characters; got " + licence);
            }
        }
        atLeast("max-pending-per-day", maxPendingPerDay, 1);
        atLeast("max-published-per-user", maxPublishedPerUser, 1);
        atLeast("report-limit-per-hour", reportLimitPerHour, 1);
        mediaBaseUrl = mediaBaseUrl == null ? "" : mediaBaseUrl.trim();
        if (!mediaBaseUrl.isEmpty()) {
            if (!mediaBaseUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalStateException(PREFIX + "media-base-url must be an https URL; got " + mediaBaseUrl);
            }
            if (!mediaBaseUrl.endsWith("/")) mediaBaseUrl = mediaBaseUrl + "/";
        }
        if (publicCacheTtl == null || publicCacheTtl.isNegative()) {
            throw new IllegalStateException(PREFIX + "public-cache-ttl must be zero or more; got " + publicCacheTtl);
        }
    }

    /** The licence id as configured, matched without regard to case; null when it is not offered. */
    public String licence(String requested) {
        if (requested == null) return null;
        String wanted = requested.trim();
        return licences.stream().filter(l -> l.equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    public boolean hasMediaBase() {
        return !mediaBaseUrl.isEmpty();
    }

    private static void atLeast(String name, long value, long min) {
        if (value < min) {
            throw new IllegalStateException(PREFIX + name + " must be >= " + min + "; got " + value);
        }
    }
}
