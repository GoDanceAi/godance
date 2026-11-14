package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * {@code GET /v1/admin/funnel} (round 10 brief 01): cohorts by ISO week of
 * sign-up, sign-ups by source, and the top gallery recipes, as counts only.
 *
 * <p>Every test puts its accounts in a week of its own in the past and asks
 * for that week, so the tests share one database without seeing each other.
 * Who may call the route is checked with every other admin route in
 * {@link AdminRolesIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class FunnelIntegrationTest {

    private static final Duration DAY = Duration.ofDays(1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired GenerationJobRepository jobs;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired JdbcTemplate jdbc;

    private String adminBearer;

    @BeforeEach
    void signInAnAdmin() {
        adminBearer = signUpOrLogIn("funnel-admin@godance.test");
        User admin = users.findByEmail("funnel-admin@godance.test").orElseThrow();
        admin.setRole(UserRole.ADMIN);
        admin.setEmailVerified(true);
        users.save(admin);
        TwoFactorFixture.turnOn(twoFactors, admin.getId());
    }

    /** The next test's sign-in is by password alone, so 2FA can't stay on. */
    @AfterEach
    void turnOffTwoFactor() {
        twoFactors.deleteAll();
    }

    // ===== Cohorts ====================================================

    @Test
    void funnel_givenACohortWithOneUserStoppingAtEachStage_expectsEachStageCounted() {
        Instant signedUp = Instant.parse("2025-03-04T12:00:00Z");   // Tuesday of 2025-W10
        signUps(3, signedUp, null);                                  // signed up, nothing more
        UUID studioOnly = signUp(signedUp, null);
        job(studioOnly, JobChannel.STUDIO, signedUp.plus(DAY), null);
        UUID paid = signUp(signedUp, null);
        job(paid, JobChannel.STUDIO, signedUp.plus(DAY), null);
        ledger(paid, CreditSource.STRIPE, 10_00, signedUp.plus(DAY.multipliedBy(2)));
        UUID graduated = signUp(signedUp, null);
        job(graduated, JobChannel.STUDIO, signedUp.plus(DAY), null);
        ledger(graduated, CreditSource.NOWPAYMENTS, 20_00, signedUp.plus(DAY.multipliedBy(2)));
        job(graduated, JobChannel.API, signedUp.plus(Duration.ofHours(84)), null);     // 3.5 days in
        job(graduated, JobChannel.API, signedUp.plus(DAY.multipliedBy(9)), null);      // a later one changes nothing

        funnel("2025-03-03", "2025-03-09")
                .jsonPath("$.cohorts.length()").isEqualTo(1)
                .jsonPath("$.cohorts[0].week").isEqualTo("2025-W10")
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(6)
                .jsonPath("$.cohorts[0].first_studio_job").isEqualTo(3)
                .jsonPath("$.cohorts[0].first_paid_top_up").isEqualTo(2)
                .jsonPath("$.cohorts[0].first_api_job").isEqualTo(1)
                .jsonPath("$.cohorts[0].median_days_to_api_job").isEqualTo(3.5);
    }

    @Test
    void funnel_givenSomeoneWhoWentStraightToTheApi_expectsAnApiJobWithoutAStudioJob() {
        Instant signedUp = Instant.parse("2025-03-11T09:00:00Z");   // 2025-W11
        signUps(4, signedUp, null);
        UUID builder = signUp(signedUp, null);
        job(builder, JobChannel.API, signedUp.plus(DAY), null);

        funnel("2025-03-10", "2025-03-16")
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(5)
                .jsonPath("$.cohorts[0].first_studio_job").isEqualTo(0)
                .jsonPath("$.cohorts[0].first_api_job").isEqualTo(1)
                .jsonPath("$.cohorts[0].median_days_to_api_job").isEqualTo(1.0);
    }

    @Test
    void funnel_givenPaidAndUnpaidCredit_expectsOnlyPaidTopUpsCounted() {
        Instant signedUp = Instant.parse("2025-04-01T12:00:00Z");   // 2025-W14
        Instant later = signedUp.plus(DAY);
        ledger(signUp(signedUp, null), CreditSource.PROMO, 1_00, later);              // sign-up credit
        ledger(signUp(signedUp, null), CreditSource.REFERRAL, 1_00, later);           // a referral reward
        ledger(signUp(signedUp, null), CreditSource.ADMIN_ADJUSTMENT, 5_00, later);   // an operator's credit
        ledger(signUp(signedUp, null), CreditSource.ADJUSTMENT, 6, later);            // a refund of a job
        ledger(signUp(signedUp, null), CreditSource.STRIPE, -10_00, later);           // a card refund, not a top-up
        UUID paid = signUp(signedUp, null);
        ledger(paid, CreditSource.PROMO, 1_00, later);
        ledger(paid, CreditSource.BINANCE_PAY, 10_00, later.plus(DAY));
        ledger(paid, CreditSource.STRIPE, 10_00, later.plus(DAY.multipliedBy(3)));    // a second top-up is still one person

        funnel("2025-03-31", "2025-04-06")
                .jsonPath("$.cohorts[0].week").isEqualTo("2025-W14")
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(6)
                .jsonPath("$.cohorts[0].first_paid_top_up").isEqualTo(1);
    }

    @Test
    void funnel_givenAWeekOfFourAndAWeekOfFive_expectsOnlyTheWeekOfFive() {
        signUps(4, Instant.parse("2025-04-29T12:00:00Z"), null);   // 2025-W18
        signUps(5, Instant.parse("2025-05-06T12:00:00Z"), null);   // 2025-W19

        funnel("2025-04-28", "2025-05-11")
                .jsonPath("$.cohorts.length()").isEqualTo(1)
                .jsonPath("$.cohorts[0].week").isEqualTo("2025-W19")
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(5);
    }

    @Test
    void funnel_givenNoApiJobsInACohort_expectsMedianNull() {
        Instant signedUp = Instant.parse("2025-05-20T12:00:00Z");   // 2025-W21
        signUps(4, signedUp, null);
        job(signUp(signedUp, null), JobChannel.STUDIO, signedUp.plus(DAY), null);

        funnel("2025-05-19", "2025-05-25")
                .jsonPath("$.cohorts[0].first_api_job").isEqualTo(0)
                // Present, and null: not left out.
                .jsonPath("$.cohorts[0]").value(cohort ->
                        assertThat(((Map<?, ?>) cohort).containsKey("median_days_to_api_job")).isTrue())
                .jsonPath("$.cohorts[0].median_days_to_api_job").value(median -> assertThat(median).isNull());
    }

    @Test
    void funnel_givenAnEvenNumberOfApiUsers_expectsTheMedianBetweenTheMiddleTwo() {
        Instant signedUp = Instant.parse("2025-06-03T12:00:00Z");   // 2025-W23
        signUps(3, signedUp, null);
        job(signUp(signedUp, null), JobChannel.API, signedUp.plus(DAY.multipliedBy(2)), null);
        job(signUp(signedUp, null), JobChannel.API, signedUp.plus(DAY.multipliedBy(5)), null);

        funnel("2025-06-02", "2025-06-08")
                .jsonPath("$.cohorts[0].first_api_job").isEqualTo(2)
                .jsonPath("$.cohorts[0].median_days_to_api_job").isEqualTo(3.5);
    }

    @Test
    void funnel_givenSignUpsAtTheTurnOfTheYear_expectsTheIsoWeekAndItsYear() {
        signUps(5, Instant.parse("2024-12-31T10:00:00Z"), null);   // a Tuesday, in ISO week 1 of 2025

        funnel("2024-12-30", "2025-01-05")
                .jsonPath("$.cohorts.length()").isEqualTo(1)
                .jsonPath("$.cohorts[0].week").isEqualTo("2025-W01");
    }

    @Test
    void funnel_givenSignUpsEitherSideOfMidnightUtcOnSunday_expectsThemInDifferentWeeks() {
        signUps(5, Instant.parse("2025-06-15T23:30:00Z"), null);   // Sunday: still 2025-W24
        signUps(6, Instant.parse("2025-06-16T00:30:00Z"), null);   // Monday: 2025-W25

        funnel("2025-06-09", "2025-06-22")
                .jsonPath("$.cohorts.length()").isEqualTo(2)
                .jsonPath("$.cohorts[0].week").isEqualTo("2025-W24")
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(5)
                .jsonPath("$.cohorts[1].week").isEqualTo("2025-W25")
                .jsonPath("$.cohorts[1].signed_up").isEqualTo(6);
    }

    @Test
    void funnel_givenSignUpsOutsideTheRange_expectsThemLeftOut() {
        signUps(5, Instant.parse("2025-07-01T12:00:00Z"), null);   // 2025-W27
        signUps(5, Instant.parse("2025-07-08T12:00:00Z"), null);   // 2025-W28

        // Both ends are inclusive days: the range is the first week only.
        funnel("2025-06-30", "2025-07-06")
                .jsonPath("$.cohorts.length()").isEqualTo(1)
                .jsonPath("$.cohorts[0].week").isEqualTo("2025-W27");
    }

    // ===== Sources ====================================================

    @Test
    void funnel_givenEachKindOfSource_expectsTheContractsBucketsInItsOrderOfPrecedence() {
        Instant signedUp = Instant.parse("2025-08-05T12:00:00Z");   // 2025-W32
        // A gallery recipe in the first touch wins over a campaign and over a referral.
        List<UUID> gallery = signUps(6, signedUp,
                "{\"gallery_recipe\":\"paper-lantern-street-at-dusk\",\"utm_source\":\"x\"}");
        UUID referrer = signUp(signedUp.minus(DAY.multipliedBy(60)), null);    // outside the range
        referral(referrer, gallery.get(0), signedUp);
        // A referral wins over a campaign.
        List<UUID> invited = signUps(5, signedUp, null);
        invited.forEach(id -> referral(referrer, id, signedUp));
        jdbc.update("UPDATE users SET first_touch = cast(? AS jsonb), first_touch_at = created_at WHERE id = ?",
                "{\"utm_source\":\"x\"}", invited.get(0));
        // Campaigns are grouped whatever their case.
        signUps(4, signedUp, "{\"utm_source\":\"Newsletter\",\"utm_medium\":\"email\"}");
        signUps(3, signedUp, "{\"utm_source\":\"newsletter\"}");
        // Campaigns too small to show on their own are counted together.
        signUps(3, signedUp, "{\"utm_source\":\"reddit\"}");
        signUps(2, signedUp, "{\"utm_source\":\"hn\"}");
        // A first touch with no campaign and no recipe, and none at all.
        signUps(2, signedUp, "{\"landing_path\":\"/signup\",\"referrer_host\":\"example.org\"}");
        List<UUID> none = signUps(3, signedUp, null);
        job(none.get(0), JobChannel.STUDIO, signedUp.plus(DAY), null);
        ledger(gallery.get(1), CreditSource.STRIPE, 10_00, signedUp.plus(DAY));
        job(gallery.get(1), JobChannel.STUDIO, signedUp.plus(DAY), null);
        job(gallery.get(2), JobChannel.API, signedUp.plus(DAY), null);

        funnel("2025-08-04", "2025-08-10")
                .jsonPath("$.by_source.length()").isEqualTo(5)
                // Largest first; equal counts by name.
                .jsonPath("$.by_source[0].source").isEqualTo("utm:newsletter")
                .jsonPath("$.by_source[0].signed_up").isEqualTo(7)
                .jsonPath("$.by_source[1].source").isEqualTo("gallery")
                .jsonPath("$.by_source[1].signed_up").isEqualTo(6)
                .jsonPath("$.by_source[1].first_studio_job").isEqualTo(1)
                .jsonPath("$.by_source[1].first_paid_top_up").isEqualTo(1)
                .jsonPath("$.by_source[1].first_api_job").isEqualTo(1)
                .jsonPath("$.by_source[2].source").isEqualTo("none")
                .jsonPath("$.by_source[2].signed_up").isEqualTo(5)
                .jsonPath("$.by_source[2].first_studio_job").isEqualTo(1)
                .jsonPath("$.by_source[3].source").isEqualTo("referral")
                .jsonPath("$.by_source[3].signed_up").isEqualTo(5)
                .jsonPath("$.by_source[4].source").isEqualTo("utm:other")
                .jsonPath("$.by_source[4].signed_up").isEqualTo(5);
    }

    @Test
    void funnel_givenElevenCampaigns_expectsTheTenLargestNamedAndTheRestAsOther() {
        Instant signedUp = Instant.parse("2025-09-02T12:00:00Z");   // 2025-W36
        for (int i = 1; i <= 10; i++) {
            signUps(6, signedUp, "{\"utm_source\":\"campaign-%02d\"}".formatted(i));
        }
        signUps(5, signedUp, "{\"utm_source\":\"campaign-11\"}");   // large enough to show, but eleventh

        funnel("2025-09-01", "2025-09-07")
                .jsonPath("$.by_source.length()").isEqualTo(11)
                .jsonPath("$.by_source[?(@.source == 'utm:campaign-01')].signed_up").isEqualTo(6)
                .jsonPath("$.by_source[?(@.source == 'utm:campaign-10')].signed_up").isEqualTo(6)
                .jsonPath("$.by_source[?(@.source == 'utm:campaign-11')]").isEmpty()
                .jsonPath("$.by_source[?(@.source == 'utm:other')].signed_up").isEqualTo(5);
    }

    @Test
    void funnel_givenASourceOfFour_expectsItLeftOut() {
        Instant signedUp = Instant.parse("2025-09-16T12:00:00Z");   // 2025-W38
        signUps(4, signedUp, "{\"gallery_recipe\":\"koi-pond-in-the-rain\"}");
        signUps(4, signedUp, "{\"utm_source\":\"tiny\"}");
        signUps(5, signedUp, null);

        funnel("2025-09-15", "2025-09-21")
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(13)
                .jsonPath("$.by_source.length()").isEqualTo(1)
                .jsonPath("$.by_source[0].source").isEqualTo("none")
                .jsonPath("$.by_source[0].signed_up").isEqualTo(5);
    }

    // ===== Gallery recipes ============================================

    @Test
    void funnel_givenRecipesRunInTheRange_expectsPeopleJobsAndThoseWithAnApiJob() {
        Instant day = Instant.parse("2025-10-07T12:00:00Z");
        List<UUID> people = signUps(6, day.minus(DAY.multipliedBy(200)), null);   // signed up long before
        for (UUID person : people.subList(0, 5)) {
            job(person, JobChannel.STUDIO, day, "paper-lantern-street-at-dusk");
        }
        job(people.get(0), JobChannel.STUDIO, day.plus(DAY), "paper-lantern-street-at-dusk");
        job(people.get(0), JobChannel.API, day.plus(DAY), "paper-lantern-street-at-dusk");
        job(people.get(1), JobChannel.API, day.plus(DAY.multipliedBy(40)), null);   // an API job, any time
        // Four people: too few to show.
        for (UUID person : people.subList(0, 4)) {
            job(person, JobChannel.STUDIO, day, "koi-pond-in-the-rain");
        }
        // Outside the range: not counted.
        job(people.get(5), JobChannel.STUDIO, day.plus(DAY.multipliedBy(30)), "paper-lantern-street-at-dusk");

        funnel("2025-10-06", "2025-10-12")
                .jsonPath("$.gallery_recipes.length()").isEqualTo(1)
                .jsonPath("$.gallery_recipes[0].slug").isEqualTo("paper-lantern-street-at-dusk")
                .jsonPath("$.gallery_recipes[0].users").isEqualTo(5)
                .jsonPath("$.gallery_recipes[0].jobs").isEqualTo(7)
                .jsonPath("$.gallery_recipes[0].users_with_api_job").isEqualTo(2);
    }

    @Test
    void funnel_givenTwoRecipes_expectsTheOneMorePeopleRanFirst() {
        Instant day = Instant.parse("2025-10-21T12:00:00Z");
        List<UUID> people = signUps(7, day.minus(DAY.multipliedBy(200)), null);
        people.subList(0, 5).forEach(p -> job(p, JobChannel.STUDIO, day, "five-people"));
        people.forEach(p -> job(p, JobChannel.STUDIO, day, "seven-people"));
        // More jobs do not outrank more people.
        for (int i = 0; i < 20; i++) job(people.get(0), JobChannel.STUDIO, day, "five-people");

        funnel("2025-10-20", "2025-10-26")
                .jsonPath("$.gallery_recipes.length()").isEqualTo(2)
                .jsonPath("$.gallery_recipes[0].slug").isEqualTo("seven-people")
                .jsonPath("$.gallery_recipes[0].users").isEqualTo(7)
                .jsonPath("$.gallery_recipes[1].slug").isEqualTo("five-people")
                .jsonPath("$.gallery_recipes[1].jobs").isEqualTo(25);
    }

    // ===== Counts only ================================================

    @Test
    void funnel_givenAnyReport_expectsNoUserIdEmailOrName() {
        Instant signedUp = Instant.parse("2025-11-04T12:00:00Z");   // 2025-W45
        List<UUID> people = signUps(5, signedUp, "{\"gallery_recipe\":\"paper-lantern-street-at-dusk\"}");
        people.forEach(p -> job(p, JobChannel.API, signedUp.plus(DAY), "paper-lantern-street-at-dusk"));

        String body = new String(http.get().uri("/v1/admin/funnel?from=2025-11-03&to=2025-11-09")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cohorts[0].signed_up").isEqualTo(5)
                .jsonPath("$.by_source[0].source").isEqualTo("gallery")
                .jsonPath("$.gallery_recipes[0].users").isEqualTo(5)
                .returnResult().getResponseBody());

        people.forEach(p -> assertThat(body).doesNotContain(p.toString()));
        assertThat(body).doesNotContain("@").doesNotContain("Funnel Fixture");
        // Three lists of counts and nothing else.
        funnel("2025-11-03", "2025-11-09").jsonPath("$.length()").isEqualTo(3);
    }

    // ===== The range ==================================================

    @Test
    void funnel_givenNoRange_expectsTheLast30Days() {
        signUps(5, Instant.now().minus(DAY.multipliedBy(3)), "{\"utm_source\":\"recent\"}");
        signUps(5, Instant.now().minus(DAY.multipliedBy(45)), "{\"utm_source\":\"older\"}");

        http.get().uri("/v1/admin/funnel")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.by_source[?(@.source == 'utm:recent')].signed_up").isEqualTo(5)
                .jsonPath("$.by_source[?(@.source == 'utm:older')]").isEmpty();
    }

    @Test
    void funnel_givenAnEmptyRange_expectsThreeEmptyLists() {
        funnel("2019-01-01", "2019-01-31")
                .jsonPath("$.cohorts").isEmpty()
                .jsonPath("$.by_source").isEmpty()
                .jsonPath("$.gallery_recipes").isEmpty();
    }

    @Test
    void funnel_givenBadDates_expects400() {
        for (String query : List.of("from=2025-13-01", "from=yesterday", "from=2025-02-01&to=2025-01-01",
                "from=2023-01-01&to=2025-01-01")) {
            http.get().uri("/v1/admin/funnel?" + query)
                    .header("Authorization", adminBearer)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void funnel_givenACustomer_expects403() {
        String customer = signUpOrLogIn("funnel-customer@godance.test");

        http.get().uri("/v1/admin/funnel")
                .header("Authorization", customer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== Fixtures ===================================================

    private WebTestClient.BodyContentSpec funnel(String from, String to) {
        return http.get().uri("/v1/admin/funnel?from=" + from + "&to=" + to)
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    /** {@code count} accounts created at {@code createdAt}, each with this first touch (a JSON object, or null). */
    private List<UUID> signUps(int count, Instant createdAt, String firstTouch) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(signUp(createdAt, firstTouch));
        return ids;
    }

    private UUID signUp(Instant createdAt, String firstTouch) {
        User u = new User();
        u.setEmail("funnel-" + UUID.randomUUID() + "@godance.test");
        u.setFullName("Funnel Fixture");
        u.setRole(UserRole.USER);
        UUID id = users.save(u).getId();
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
        if (firstTouch != null) {
            jdbc.update("UPDATE users SET first_touch = cast(? AS jsonb), first_touch_at = created_at WHERE id = ?",
                    firstTouch, id);
        }
        return id;
    }

    private void job(UUID userId, JobChannel channel, Instant createdAt, String galleryRecipe) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "a fixture"));
        job.setStatus(JobStatus.DONE);
        job.setEstimatedCostCents(6L);
        job.setActualCostCents(6L);
        job.setRequestId("img_" + UUID.randomUUID());
        job.setCreatedAt(createdAt);
        job.setChannel(channel);
        job.setGalleryRecipe(galleryRecipe);
        jobs.save(job);
    }

    private void ledger(UUID userId, CreditSource source, long deltaCents, Instant occurredAt) {
        CreditLedger entry = new CreditLedger();
        entry.setUserId(userId);
        entry.setDeltaCents(deltaCents);
        entry.setSource(source);
        entry.setIdempotencyKey("funnel-" + UUID.randomUUID());
        entry.setOccurredAt(occurredAt);
        ledgerRows.save(entry);
    }

    private void referral(UUID referrer, UUID invited, Instant claimedAt) {
        jdbc.update("INSERT INTO referrals (id, referrer_id, invited_user_id, claimed_at, status) "
                + "VALUES (?, ?, ?, ?, 'PENDING')", UUID.randomUUID(), referrer, invited, Timestamp.from(claimedAt));
    }

    private String signUpOrLogIn(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Console Test"))
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }
}
