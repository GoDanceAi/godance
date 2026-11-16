package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.OpenJobsGuard;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.JobStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.web.CommunityFixture.Ran;
import ai.godance.web.CommunityFixture.Signed;

/**
 * Who may set a profile and publish, and what a submission may hold (round
 * 14 brief 01): adult accounts with a verified email and a profile; one to
 * four of their own finished, unpurged runs of the recipe, with no input
 * image; no input slots while people are not allowed in uploads; and the
 * daily and total limits. Small limits here so they can be reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class PublishingRulesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.community.enabled", () -> "true");
        r.add("godance.community.max-pending-per-day", () -> "3");
        r.add("godance.community.max-published-per-user", () -> "2");
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired CreditLedgerService ledger;
    @Autowired DataRetentionService retention;
    @Autowired JdbcTemplate jdbc;

    CommunityFixture community;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        CommunityFixture.stubImage(openAiMock);
        community = new CommunityFixture(http, users, twoFactors, ledger);
    }

    // ===== Profiles =====================================================

    @Test
    void putProfile_givenUnverifiedEmail_expects403WithTheReason() {
        Signed u = community.account("unverified", 0, false);
        Map<String, Object> problem = problem(community.putProfile(u, CommunityFixture.freshHandle("u"), "U", null), 403);
        assertThat(problem).containsEntry("code", "community_not_allowed").containsEntry("reason", "email_not_verified");
        assertThat(profile(u)).containsEntry("cannot_publish", "email_not_verified").containsEntry("handle", null);
    }

    @Test
    void putProfile_givenChildAccount_expects403NotAdult() {
        Signed u = community.account("child", 0, true);
        User child = users.findById(u.id()).orElseThrow();
        child.setAgeStatus(AgeStatus.CONSENT_GRANTED);
        users.save(child);
        Map<String, Object> problem = problem(community.putProfile(u, CommunityFixture.freshHandle("c"), "C", null), 403);
        assertThat(problem).containsEntry("code", "community_not_allowed").containsEntry("reason", "not_adult");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-lead", "trail-", "two--hyphens", "has space", "under_score", "admin", "godance",
            "support", "moderator-1"})
    void putProfile_givenHandleTheRulesRefuse_expects400InvalidProfile(String handle) {
        Signed u = community.author(0);
        assertThat(CommunityFixture.codeOf(community.putProfile(u, handle, "Name", null), 400)).isEqualTo("invalid_profile");
    }

    @Test
    void putProfile_givenHandleOfAnotherAccount_expects409HandleTaken() {
        Signed first = community.author(0);
        String handle = community.profile(first, "taken");
        Signed second = community.author(0);
        assertThat(CommunityFixture.codeOf(community.putProfile(second, handle, "Other", null), 409)).isEqualTo("handle_taken");
        // Case does not make a handle different.
        assertThat(CommunityFixture.codeOf(community.putProfile(second, handle.toUpperCase(), "Other", null), 409))
                .isEqualTo("handle_taken");
    }

    @Test
    void putProfile_givenHttpLinkOrLongName_expects400() {
        Signed u = community.author(0);
        String handle = CommunityFixture.freshHandle("links");
        assertThat(CommunityFixture.codeOf(community.putProfile(u, handle, "Name", "http://example.test"), 400))
                .isEqualTo("invalid_profile");
        assertThat(CommunityFixture.codeOf(community.putProfile(u, handle, "x".repeat(61), null), 400))
                .isEqualTo("invalid_profile");
        community.putProfile(u, handle, "Name", "https://example.test/me").expectStatus().isOk();
    }

    @Test
    void putProfile_beforeApproval_expectsHandleChangeable() {
        Signed u = community.author(0);
        community.profile(u, "first");
        String second = CommunityFixture.freshHandle("second");
        community.putProfile(u, second, "Renamed", null).expectStatus().isOk();
        assertThat(profile(u)).containsEntry("handle", second).containsEntry("approved", false)
                .containsEntry("cannot_publish", null);
    }

    @Test
    void putProfile_afterApproval_expectsHandleLockedAndChangesAwaitingReview() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        CommunityFixture.Published pub = community.publicRecipe(moderator, "locked", List.of());

        assertThat(CommunityFixture.codeOf(community.putProfile(pub.author(), CommunityFixture.freshHandle("moved"),
                "Display locked", null), 409)).isEqualTo("handle_locked");

        community.putProfile(pub.author(), pub.handle(), "A new name", "https://example.test/new").expectStatus().isOk();
        assertThat(profile(pub.author())).containsEntry("approved", true).containsEntry("change_awaiting_review", true);
        // The public page keeps what the moderator approved.
        Map<String, Object> shown = publicProfile(pub.handle());
        assertThat(shown).containsEntry("display_name", "Display locked").containsEntry("link", "https://example.test/locked");
    }

    // ===== Who may publish ==============================================

    @Test
    void publish_givenNoProfile_expects409ProfileRequired() {
        Signed u = community.author(100_00L);
        Ran ran = community.recipeWithRun(u, "No profile yet");
        assertThat(CommunityFixture.codeOf(community.postPublish(u, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId()))), 409)).isEqualTo("profile_required");
    }

    @Test
    void publish_givenEmailNoLongerVerified_expects403() {
        Signed u = community.author(100_00L);
        community.profile(u, "unverified-later");
        Ran ran = community.recipeWithRun(u, "Then unverified");
        User user = users.findById(u.id()).orElseThrow();
        user.setEmailVerified(false);
        users.save(user);
        Map<String, Object> problem = problem(community.postPublish(u, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId()))), 403);
        assertThat(problem).containsEntry("code", "community_not_allowed");
    }

    @Test
    void publish_givenAnotherAccountsRecipe_expects404() {
        Signed owner = community.author(100_00L);
        Ran ran = community.recipeWithRun(owner, "Not yours");
        Signed other = community.author(0);
        community.profile(other, "other");
        assertThat(CommunityFixture.codeOf(community.postPublish(other, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId()))), 404)).isEqualTo("recipe_not_found");
    }

    // ===== Examples =====================================================

    @Test
    void publish_givenAnotherAccountsJob_expects400InvalidExample() {
        Signed author = community.author(100_00L);
        community.profile(author, "borrower");
        Ran mine = community.recipeWithRun(author, "Mine");
        Signed other = community.author(100_00L);
        Ran theirs = community.recipeWithRun(other, "Theirs");
        Map<String, Object> problem = problem(community.postPublish(author, mine.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(theirs.jobId()))), 400);
        assertThat(problem).containsEntry("code", "invalid_example").containsEntry("job_id", theirs.jobId().toString());
    }

    @Test
    void publish_givenRunOfAnotherRecipe_expects400InvalidExample() {
        Signed author = community.author(100_00L);
        community.profile(author, "mixer");
        Ran one = community.recipeWithRun(author, "One");
        Ran two = community.recipeWithRun(author, "Two");
        assertThat(CommunityFixture.codeOf(community.postPublish(author, one.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(two.jobId()))), 400)).isEqualTo("invalid_example");
    }

    @Test
    void publish_givenFailedJob_expects400InvalidExample() {
        Signed author = community.author(100_00L);
        community.profile(author, "failer");
        Ran ran = community.recipeWithRun(author, "Sometimes fails");
        openAiMock.resetAll();
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations")).willReturn(aResponse().withStatus(500)));
        UUID failed = community.submit(author.bearer(), Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", ran.versionId())));
        community.awaitStatus(failed, author.bearer(), JobStatus.FAILED);

        Map<String, Object> problem = problem(community.postPublish(author, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(failed))), 400);
        assertThat(problem).containsEntry("code", "invalid_example");
        assertThat((String) problem.get("detail")).contains("has not finished");
    }

    @Test
    void publish_givenPurgedJob_expects400InvalidExample() {
        Signed author = community.author(100_00L);
        community.profile(author, "purger");
        Ran ran = community.recipeWithRun(author, "Old run");
        assertThat(retention.purgeJob(ran.jobId(), Instant.now())).isTrue();
        Map<String, Object> problem = problem(community.postPublish(author, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId()))), 400);
        assertThat(problem).containsEntry("code", "invalid_example");
        assertThat((String) problem.get("detail")).contains("files deleted");
    }

    @Test
    void publish_givenExampleThatUsedAnInputImage_expects400ExampleHasInputs() {
        Signed author = community.author(100_00L);
        community.profile(author, "editor");
        Ran ran = community.recipeWithRun(author, "Edited");
        UUID other = community.runVersion(author, ran.versionId(), Map.of("thing", "lamp"));
        // As an edit records it: the run was given another of the account's outputs as its image.
        jdbc.update("INSERT INTO generation_job_inputs (job_id, position, role, source_job_id, source_index) "
                + "VALUES (?, 0, 'image', ?, 0)", other, ran.jobId());
        assertThat(CommunityFixture.codeOf(community.postPublish(author, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId(), other))), 400))
                .isEqualTo("example_has_inputs");
    }

    @Test
    void publish_givenVersionWithInputSlotsWhilePeopleNotAllowed_expects400() {
        Signed author = community.author(100_00L);
        community.profile(author, "slots");
        Ran plain = community.recipeWithRun(author, "Slots later");
        // A second version with an input slot; its example is a run of version 1.
        http.post().uri("/v1/recipes/{id}/versions", plain.recipeId())
                .header("Authorization", author.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("template", "Put {{thing}} on the table in the photo.", "model", "gpt-image-2",
                        "variables", List.of(Map.of("name", "thing", "default", "teapot")),
                        "inputs", List.of(Map.of("slot", "table photo", "role", "image", "hint", "your table"))))
                .exchange()
                .expectStatus().isCreated();
        assertThat(CommunityFixture.codeOf(community.postPublish(author, plain.recipeId(),
                CommunityFixture.publishBody(2, "CC-BY-4.0", List.of(plain.jobId()))), 400))
                .isEqualTo("published_inputs_not_allowed");
    }

    @Test
    void publish_givenBadVersionLicenceTagsOrCount_expects400InvalidPublish() {
        Signed author = community.author(100_00L);
        community.profile(author, "sloppy");
        Ran ran = community.recipeWithRun(author, "Sloppy");
        List<UUID> one = List.of(ran.jobId());
        assertThat(code400(author, ran, CommunityFixture.publishBody(9, "CC-BY-4.0", one))).isEqualTo("invalid_publish");
        Map<String, Object> licence = problem(community.postPublish(author, ran.recipeId(),
                CommunityFixture.publishBody(1, "All rights reserved", one)), 400);
        assertThat(licence).containsEntry("code", "invalid_publish");
        assertThat(licence.get("licences")).isEqualTo(List.of("CC-BY-4.0", "CC0-1.0"));
        assertThat(code400(author, ran, CommunityFixture.publishBody(1, "CC0-1.0", one, List.of("Not A Tag"))))
                .isEqualTo("invalid_publish");
        assertThat(code400(author, ran, CommunityFixture.publishBody(1, "CC0-1.0", one,
                List.of("a", "b", "c", "d", "e", "f")))).isEqualTo("invalid_publish");
        assertThat(code400(author, ran, CommunityFixture.publishBody(1, "CC0-1.0", List.of()))).isEqualTo("invalid_publish");
        List<UUID> five = List.of(ran.jobId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(code400(author, ran, CommunityFixture.publishBody(1, "CC0-1.0", five))).isEqualTo("invalid_publish");
        // The licence is matched without regard to case, and stored as offered.
        Map<String, Object> ok = community.postPublish(author, ran.recipeId(),
                        CommunityFixture.publishBody(1, "cc0-1.0", one, List.of("still-life")))
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
        assertThat(ok).containsEntry("licence", "CC0-1.0").containsEntry("status", "PENDING")
                .containsEntry("tags", List.of("still-life")).containsEntry("job_ids", List.of(ran.jobId().toString()));
    }

    // ===== Limits =======================================================

    @Test
    void publish_givenRecipeAlreadyPending_expects409AlreadyPublished() {
        Signed author = community.author(100_00L);
        community.profile(author, "twice");
        Ran ran = community.recipeWithRun(author, "Twice");
        community.publish(author, ran, List.of(ran.jobId()));
        assertThat(CommunityFixture.codeOf(community.postPublish(author, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId()))), 409)).isEqualTo("already_published");
    }

    @Test
    void publish_givenPendingAndPublicAtTheLimit_expects409LimitReached() {
        Signed author = community.author(100_00L);
        community.profile(author, "prolific");
        for (int i = 0; i < 2; i++) {
            Ran ran = community.recipeWithRun(author, "Prolific " + i);
            community.publish(author, ran, List.of(ran.jobId()));
        }
        Ran third = community.recipeWithRun(author, "Prolific 3");
        assertThat(CommunityFixture.codeOf(community.postPublish(author, third.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(third.jobId()))), 409))
                .isEqualTo("publish_limit_reached");
    }

    @Test
    void publish_givenTodaysSubmissionsAtTheLimit_expects429LimitReached() {
        Signed author = community.author(100_00L);
        community.profile(author, "eager");
        for (int i = 0; i < 3; i++) {
            Ran ran = community.recipeWithRun(author, "Eager " + i);
            community.publish(author, ran, List.of(ran.jobId()));
            // Unpublished at once, so only the daily count can stop the next one.
            http.delete().uri("/v1/recipes/{id}/publish", ran.recipeId())
                    .header("Authorization", author.bearer()).exchange().expectStatus().isNoContent();
        }
        Ran fourth = community.recipeWithRun(author, "Eager 4");
        assertThat(CommunityFixture.codeOf(community.postPublish(author, fourth.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(fourth.jobId()))), 429))
                .isEqualTo("publish_limit_reached");
    }

    // ===== Helpers ======================================================

    private String code400(Signed author, Ran ran, Map<String, Object> body) {
        return CommunityFixture.codeOf(community.postPublish(author, ran.recipeId(), body), 400);
    }

    private Map<String, Object> profile(Signed u) {
        return http.get().uri("/v1/profile").header("Authorization", u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
    }

    private Map<String, Object> publicProfile(String handle) {
        return http.get().uri("/v1/public/profiles/{handle}", handle).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
    }

    private static Map<String, Object> problem(WebTestClient.ResponseSpec response, int status) {
        return response.expectStatus().isEqualTo(status)
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
    }
}
