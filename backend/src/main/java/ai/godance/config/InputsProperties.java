package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Input files (round 11 brief 01): images an account uploads to use in a
 * generation. Everything ships off: with {@code enabled} false nothing is
 * accepted anywhere.
 *
 * @param enabled               the switch. False: no upload, no terms
 *                              acceptance and no request that names an input
 *                              is accepted; each answers {@code inputs_disabled}
 * @param allowPeople           whether an upload may show a person. False:
 *                              every upload is checked, and one that shows a
 *                              person, or that cannot be checked, is refused
 * @param checkModel            the vision chat model that runs the person
 *                              check (brief 02). Empty: there is no check,
 *                              so with {@code allowPeople} false every
 *                              upload answers {@code input_check_unavailable}
 * @param termsVersion          the current version of the input terms; an
 *                              account that accepted an older one must
 *                              accept again
 * @param unusedTtlHours        how long a file nothing uses is kept
 * @param maxBytes              the largest file accepted
 * @param maxMegapixels         the most pixels an image may have, in millions
 * @param maxFilesPerAccount    the most files one account may have stored
 *                              at once, used or not
 * @param signedUrlTtl          how long a link to an input lasts when its
 *                              owner reads it ({@code /v1/files/{id}/content},
 *                              a job's {@code inputs[].url})
 * @param providerUrlTtl        how long a link lasts when it is given to a
 *                              provider that fetches inputs by URL
 * @param uploadLimitPerAccount uploads one account may try per hour
 * @param uploadLimitPerIp      uploads one client address may try per hour
 */
@ConfigurationProperties(prefix = "godance.inputs")
public record InputsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean allowPeople,
        @DefaultValue("") String checkModel,
        @DefaultValue("1") int termsVersion,
        @DefaultValue("24") int unusedTtlHours,
        @DefaultValue("20MB") DataSize maxBytes,
        @DefaultValue("40") int maxMegapixels,
        @DefaultValue("200") int maxFilesPerAccount,
        @DefaultValue("5m") Duration signedUrlTtl,
        @DefaultValue("1h") Duration providerUrlTtl,
        @DefaultValue("60") int uploadLimitPerAccount,
        @DefaultValue("120") int uploadLimitPerIp
) {
    private static final String PREFIX = "godance.inputs.";

    /** SigV4 signs a link for at most seven days. */
    private static final Duration MAX_URL_TTL = Duration.ofDays(7);

    public InputsProperties {
        checkModel = checkModel == null ? "" : checkModel.trim();
        atLeast("terms-version", termsVersion, 1);
        atLeast("unused-ttl-hours", unusedTtlHours, 1);
        atLeast("max-megapixels", maxMegapixels, 1);
        atLeast("max-files-per-account", maxFilesPerAccount, 1);
        atLeast("upload-limit-per-account", uploadLimitPerAccount, 1);
        atLeast("upload-limit-per-ip", uploadLimitPerIp, 1);
        if (maxBytes == null || maxBytes.toBytes() < 1 || maxBytes.toBytes() > Integer.MAX_VALUE - 16) {
            throw new IllegalStateException(PREFIX + "max-bytes must be between 1 byte and 2 GB; got " + maxBytes);
        }
        urlTtl("signed-url-ttl", signedUrlTtl);
        urlTtl("provider-url-ttl", providerUrlTtl);
    }

    /** {@link #unusedTtlHours} as a duration. */
    public Duration unusedTtl() {
        return Duration.ofHours(unusedTtlHours);
    }

    /** {@link #maxMegapixels} in pixels. */
    public long maxPixels() {
        return maxMegapixels * 1_000_000L;
    }

    /** Whether a person check is configured at all. */
    public boolean hasCheckModel() {
        return !checkModel.isEmpty();
    }

    private static void atLeast(String name, long value, long min) {
        if (value < min) {
            throw new IllegalStateException(PREFIX + name + " must be >= " + min + "; got " + value);
        }
    }

    private static void urlTtl(String name, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_URL_TTL) > 0) {
            throw new IllegalStateException(PREFIX + name + " must be positive and at most 7 days; got " + ttl);
        }
    }
}
