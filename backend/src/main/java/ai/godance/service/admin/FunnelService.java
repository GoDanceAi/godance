package ai.godance.service.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The funnel report ({@code GET /v1/admin/funnel}, round 10 brief 01): of
 * the people who signed up in a date range, how many went on to make a job
 * in the Studio, to pay for a top-up, and to make a job with an API key.
 *
 * <p>Computed in SQL over {@code users}, {@code generation_jobs},
 * {@code credit_ledger} and {@code referrals}; no user is loaded. Each step
 * is the earliest qualifying row a user has, at any time: the range picks
 * who is counted, by when they signed up, not what they did inside it. The
 * steps are counted independently, so someone who went straight to the API
 * is an API job without a Studio job.
 *
 * <p><b>Counts only.</b> Nothing here returns a user id, email or name, and
 * a group of fewer than {@link #MIN_GROUP} people is left out, so nobody
 * can be picked out of a small one. Counts inside a group that is shown may
 * be small: "4 of these 120 made an API job" names nobody.
 */
@Service
public class FunnelService {

    /** The fewest people a week, a source or a recipe must have to be reported. */
    public static final int MIN_GROUP = 5;

    /** How many campaign sources get a row of their own; the rest are {@code utm:other}. */
    static final int TOP_UTM_SOURCES = 10;

    /** How many recipes the report lists. */
    static final int TOP_RECIPES = 50;

    /**
     * Each user who signed up in the range, with what brought them and when
     * they first reached each step. A paid top-up is a positive STRIPE,
     * NOWPAYMENTS or BINANCE_PAY ledger entry; promotional and referral
     * credit does not count. The source is decided in the contract's order:
     * a gallery recipe in the first touch, then a referral, then a campaign.
     */
    private static final String FIRSTS = """
            WITH firsts AS (
                SELECT u.id,
                       u.created_at,
                       CASE
                           WHEN u.first_touch ->> 'gallery_recipe' IS NOT NULL THEN 'gallery'
                           WHEN EXISTS (SELECT 1 FROM referrals r WHERE r.invited_user_id = u.id) THEN 'referral'
                           WHEN u.first_touch ->> 'utm_source' IS NOT NULL
                               THEN 'utm:' || lower(u.first_touch ->> 'utm_source')
                           ELSE 'none'
                       END AS source,
                       (SELECT min(j.created_at) FROM generation_jobs j
                         WHERE j.user_id = u.id AND j.channel = 'STUDIO') AS first_studio_job,
                       (SELECT min(j.created_at) FROM generation_jobs j
                         WHERE j.user_id = u.id AND j.channel = 'API') AS first_api_job,
                       (SELECT min(l.occurred_at) FROM credit_ledger l
                         WHERE l.user_id = u.id AND l.delta_cents > 0
                           AND l.source IN ('STRIPE', 'NOWPAYMENTS', 'BINANCE_PAY')) AS first_paid_top_up
                  FROM users u
                 WHERE u.created_at >= :from AND u.created_at < :to
            )
            """;

    /** By UTC ISO week of sign-up, e.g. {@code 2026-W47}. The median is in days, to one decimal. */
    private static final String COHORTS = FIRSTS + """
            SELECT to_char(f.created_at AT TIME ZONE 'UTC', 'IYYY-"W"IW') AS week,
                   count(*)                   AS signed_up,
                   count(f.first_studio_job)  AS first_studio_job,
                   count(f.first_paid_top_up) AS first_paid_top_up,
                   count(f.first_api_job)     AS first_api_job,
                   round(cast(percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY extract(epoch FROM f.first_api_job - f.created_at) / 86400.0) AS numeric), 1)
                                              AS median_days_to_api_job
              FROM firsts f
             GROUP BY 1
            HAVING count(*) >= :minGroup
             ORDER BY 1
            """;

    /**
     * By source. A campaign gets a row of its own when it is one of the ten
     * largest and has enough people to be shown; every other campaign is
     * counted together as {@code utm:other}, so small ones are not lost.
     */
    private static final String BY_SOURCE = FIRSTS + """
            , campaigns AS (
                SELECT f.source, count(*) AS people,
                       row_number() OVER (ORDER BY count(*) DESC, f.source) AS place
                  FROM firsts f
                 WHERE f.source LIKE 'utm:%'
                 GROUP BY f.source
            ), grouped AS (
                SELECT f.*,
                       CASE
                           WHEN f.source NOT LIKE 'utm:%' THEN f.source
                           WHEN EXISTS (SELECT 1 FROM campaigns c
                                         WHERE c.source = f.source
                                           AND c.place <= :topUtm AND c.people >= :minGroup) THEN f.source
                           ELSE 'utm:other'
                       END AS bucket
                  FROM firsts f
            )
            SELECT g.bucket                   AS source,
                   count(*)                   AS signed_up,
                   count(g.first_studio_job)  AS first_studio_job,
                   count(g.first_paid_top_up) AS first_paid_top_up,
                   count(g.first_api_job)     AS first_api_job
              FROM grouped g
             GROUP BY g.bucket
            HAVING count(*) >= :minGroup
             ORDER BY count(*) DESC, g.bucket
            """;

    /**
     * The recipes named by jobs created in the range, by how many different
     * people ran them. {@code users_with_api_job} is how many of those
     * people have made a job with an API key, at any time.
     */
    private static final String GALLERY_RECIPES = """
            SELECT j.gallery_recipe          AS slug,
                   count(DISTINCT j.user_id) AS users,
                   count(*)                  AS jobs,
                   count(DISTINCT j.user_id) FILTER (WHERE EXISTS (
                       SELECT 1 FROM generation_jobs a
                        WHERE a.user_id = j.user_id AND a.channel = 'API')) AS users_with_api_job
              FROM generation_jobs j
             WHERE j.gallery_recipe IS NOT NULL
               AND j.created_at >= :from AND j.created_at < :to
             GROUP BY j.gallery_recipe
            HAVING count(DISTINCT j.user_id) >= :minGroup
             ORDER BY users DESC, jobs DESC, slug
             LIMIT :topRecipes
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FunnelService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One UTC ISO week of sign-ups. {@code median_days_to_api_job} is null when none of them has an API job. */
    public record Cohort(
            String week,
            @JsonProperty("signed_up")         long signedUp,
            @JsonProperty("first_studio_job")  long firstStudioJob,
            @JsonProperty("first_paid_top_up") long firstPaidTopUp,
            @JsonProperty("first_api_job")     long firstApiJob,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @JsonProperty("median_days_to_api_job") Double medianDaysToApiJob
    ) { }

    /** The sign-ups one source brought: {@code gallery}, {@code referral}, {@code utm:<source>}, {@code utm:other} or {@code none}. */
    public record Source(
            String source,
            @JsonProperty("signed_up")         long signedUp,
            @JsonProperty("first_studio_job")  long firstStudioJob,
            @JsonProperty("first_paid_top_up") long firstPaidTopUp,
            @JsonProperty("first_api_job")     long firstApiJob
    ) { }

    public record GalleryRecipe(
            String slug,
            long users,
            long jobs,
            @JsonProperty("users_with_api_job") long usersWithApiJob
    ) { }

    public record Funnel(
            List<Cohort> cohorts,
            @JsonProperty("by_source") List<Source> bySource,
            @JsonProperty("gallery_recipes") List<GalleryRecipe> galleryRecipes
    ) { }

    /**
     * The funnel for sign-ups between {@code from} and {@code to} (inclusive
     * calendar days, UTC), with the same checks as the usage summary.
     */
    @Transactional(readOnly = true)
    public Funnel funnel(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be <= to");
        }
        if (from.plusDays(370).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window too large (max ~1 year)");
        }
        OffsetDateTime fromTs = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toTs = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        Map<String, Object> range = Map.of("from", fromTs, "to", toTs, "minGroup", MIN_GROUP);

        List<Cohort> cohorts = jdbc.query(COHORTS, range, (rs, i) -> new Cohort(
                rs.getString("week"), rs.getLong("signed_up"), rs.getLong("first_studio_job"),
                rs.getLong("first_paid_top_up"), rs.getLong("first_api_job"),
                rs.getObject("median_days_to_api_job") == null
                        ? null : rs.getBigDecimal("median_days_to_api_job").doubleValue()));

        List<Source> sources = jdbc.query(BY_SOURCE,
                Map.of("from", fromTs, "to", toTs, "minGroup", MIN_GROUP, "topUtm", TOP_UTM_SOURCES),
                (rs, i) -> new Source(
                        rs.getString("source"), rs.getLong("signed_up"), rs.getLong("first_studio_job"),
                        rs.getLong("first_paid_top_up"), rs.getLong("first_api_job")));

        List<GalleryRecipe> recipes = jdbc.query(GALLERY_RECIPES,
                Map.of("from", fromTs, "to", toTs, "minGroup", MIN_GROUP, "topRecipes", TOP_RECIPES),
                (rs, i) -> new GalleryRecipe(
                        rs.getString("slug"), rs.getLong("users"), rs.getLong("jobs"),
                        rs.getLong("users_with_api_job")));

        return new Funnel(cohorts, sources, recipes);
    }
}
