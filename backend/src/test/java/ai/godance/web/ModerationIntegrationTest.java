package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
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

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.CommunityFixture.Published;
import ai.godance.web.CommunityFixture.Ran;
import ai.godance.web.CommunityFixture.Signed;

/**
 * Moderation, reports, follows and account closure (round 14 brief 01).
 * Each moderation step is checked with its audit event; a report puts a
 * public recipe back in the queue while it stays public; a follow shows only
 * as a count; closing an account takes everything of it out of the
 * community. A low report limit here so it can be reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ModerationIntegrationTest {

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
        r.add("godance.community.report-limit-per-hour", () -> "3");
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
    @Autowired AuditLogEntryRepository audit;
    @Autowired ObjectStorageClient storage;
    @Autowired JdbcTemplate jdbc;

    CommunityFixture community;
    Signed moderator;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        CommunityFixture.stubImage(openAiMock);
        community = new CommunityFixture(http, users, twoFactors, ledger);
        moderator = community.staff(UserRole.SUPPORT);
    }

    // ===== Transitions and their audit events ===========================

    @Test
    void approve_givenPendingSubmission_expectsPublicCopiedAndAudited() {
        Signed author = community.author(100_00L);
        community.profile(author, "approved");
        Ran ran = community.recipeWithRun(author, "To approve");
        String publicId = community.publish(author, ran, List.of(ran.jobId()));
        assertThat(events("community.published", publicId)).hasSize(1);

        // In the queue, with what a review needs.
        List<Map<String, Object>> items = community.queueItemsFor(moderator, publicId);
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item).containsEntry("why", "pending");
        @SuppressWarnings("unchecked") Map<String, Object> recipe = (Map<String, Object>) item.get("recipe");
        assertThat(recipe).containsEntry("template", "A {{thing}} on a wooden table, soft window light.")
                .containsEntry("status", "PENDING");
        assertThat(recipe.get("variables")).isEqualTo(List.of(Map.of("name", "thing", "default", "teapot")));
        @SuppressWarnings("unchecked") List<Map<String, Object>> examples = (List<Map<String, Object>>) recipe.get("examples");
        assertThat(examples).hasSize(1);
        assertThat(examples.get(0).get("url")).isNotNull();
        assertThat(examples.get(0)).containsEntry("copied", false);
        @SuppressWarnings("unchecked") Map<String, Object> author2 = (Map<String, Object>) item.get("author");
        assertThat(author2).containsEntry("email", author.email()).containsEntry("display_name", "Display approved");
        assertThat(item.get("prior_decisions")).isEqualTo(List.of());

        community.approve(moderator, publicId);

        assertThat(status(publicId)).isEqualTo("PUBLIC");
        assertThat(storage.list("community/" + publicId + "/")).hasSize(1);
        assertThat(community.queueItemsFor(moderator, publicId)).isEmpty();
        assertThat(events("community.approved", publicId)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("moderatorId", moderator.id().toString()));
        // Approving the recipe approved the profile as the moderator saw it.
        assertThat(profile(author)).containsEntry("approved", true).containsEntry("change_awaiting_review", false);
        // Approving again changes nothing more than closing reports: there are none.
        community.approve(moderator, publicId);
        assertThat(status(publicId)).isEqualTo("PUBLIC");
    }

    @Test
    void reject_givenReason_expectsRejectedWithTheReasonAndNeverPublic() {
        Signed author = community.author(100_00L);
        community.profile(author, "rejected");
        Ran ran = community.recipeWithRun(author, "To reject");
        String publicId = community.publish(author, ran, List.of(ran.jobId()));

        assertThat(CommunityFixture.codeOf(community.moderate(moderator, publicId, "reject", " "), 400))
                .isEqualTo("invalid_publish");
        community.moderate(moderator, publicId, "reject", "A real person's name in the template").expectStatus().isOk();

        Map<String, Object> mine = community.submission(author, ran.recipeId());
        assertThat(mine).containsEntry("status", "REJECTED").containsEntry("reason", "A real person's name in the template");
        http.get().uri("/v1/public/recipes/{id}", publicId).exchange().expectStatus().isNotFound();
        assertThat(events("community.rejected", publicId)).hasSize(1);
        // A rejected submission cannot then be approved or rejected again.
        assertThat(CommunityFixture.codeOf(community.moderate(moderator, publicId, "approve", null), 409))
                .isEqualTo("moderation_conflict");
        // The author may submit again: a new submission.
        String again = community.publish(author, ran, List.of(ran.jobId()));
        assertThat(again).isNotEqualTo(publicId);
        // The new one's review shows the earlier decision.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prior = (List<Map<String, Object>>) community.queueItemsFor(moderator, again).get(0)
                .get("prior_decisions");
        assertThat(prior).singleElement().satisfies(p -> assertThat(p).containsEntry("public_id", publicId)
                .containsEntry("status", "REJECTED"));
    }

    @Test
    void remove_givenPublicRecipe_expectsRemovedCopiesDeletedAndAudited() {
        Published pub = community.publicRecipe(moderator, "removed", List.of());
        assertThat(storage.list("community/" + pub.publicId() + "/")).hasSize(1);

        community.moderate(moderator, pub.publicId(), "remove", "Shows a real person").expectStatus().isOk();

        assertThat(status(pub.publicId())).isEqualTo("REMOVED");
        http.get().uri("/v1/public/recipes/{id}", pub.publicId()).exchange().expectStatus().isNotFound();
        assertThat(storage.list("community/" + pub.publicId() + "/")).isEmpty();
        assertThat(community.submission(pub.author(), pub.ran().recipeId()))
                .containsEntry("status", "REMOVED").containsEntry("reason", "Shows a real person");
        assertThat(events("community.removed", pub.publicId())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("by", "moderator"));
        assertThat(CommunityFixture.codeOf(community.moderate(moderator, pub.publicId(), "remove", "again"), 409))
                .isEqualTo("moderation_conflict");
    }

    @Test
    void approve_givenSourceJobPurgedBeforeApproval_expects409ExampleUnavailable() {
        Signed author = community.author(100_00L);
        community.profile(author, "too-late");
        Ran ran = community.recipeWithRun(author, "Purged before review");
        String publicId = community.publish(author, ran, List.of(ran.jobId()));
        jdbc.update("DELETE FROM generation_job_outputs WHERE job_id = ?", ran.jobId());

        assertThat(CommunityFixture.codeOf(community.moderate(moderator, publicId, "approve", null), 409))
                .isEqualTo("example_unavailable");
        assertThat(status(publicId)).isEqualTo("PENDING");
        assertThat(storage.list("community/" + publicId + "/")).isEmpty();
    }

    @Test
    void moderationRoutes_givenFinanceRole_expects403() {
        Signed finance = community.staff(UserRole.FINANCE);
        http.get().uri("/v1/admin/community/queue").header("Authorization", finance.bearer()).exchange()
                .expectStatus().isForbidden();
    }

    // ===== Reports ======================================================

    @Test
    void report_givenPublicRecipe_expectsBackInTheQueueAndStillPublic() {
        Published pub = community.publicRecipe(moderator, "reported", List.of());
        Signed reader = community.account("reader", 0, true);

        community.report(reader, "published_recipe", pub.publicId(), "Looks like a real singer").expectStatus().isAccepted();
        // A second report by the same account changes nothing.
        community.report(reader, "published_recipe", pub.publicId(), "Again").expectStatus().isAccepted();

        http.get().uri("/v1/public/recipes/{id}", pub.publicId()).exchange().expectStatus().isOk();
        List<Map<String, Object>> items = community.queueItemsFor(moderator, pub.publicId());
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("why", "reported");
            assertThat((List<?>) item.get("reports")).hasSize(1);
        });
        assertThat(audit.findByEventType("community.reported")).anySatisfy(e ->
                assertThat(e.getEventDetail()).containsEntry("target", pub.publicId()));
        List<Map<String, Object>> open = http.get().uri("/v1/admin/community/reports")
                .header("Authorization", moderator.bearer()).exchange().expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() { })
                .returnResult().getResponseBody();
        assertThat(open).anySatisfy(r -> assertThat(r).containsEntry("target", pub.publicId())
                .containsEntry("reason", "Looks like a real singer"));

        // The moderator keeps it: the report is closed and the recipe leaves the queue, still public.
        community.approve(moderator, pub.publicId());
        assertThat(community.queueItemsFor(moderator, pub.publicId())).isEmpty();
        assertThat(status(pub.publicId())).isEqualTo("PUBLIC");

        // Reported again, the moderator removes it.
        Signed another = community.account("another", 0, true);
        community.report(another, "published_recipe", pub.publicId(), "Still a real singer").expectStatus().isAccepted();
        community.moderate(moderator, pub.publicId(), "remove", "Real person").expectStatus().isOk();
        assertThat(community.queueItemsFor(moderator, pub.publicId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports r JOIN published_recipes p ON p.id = r.published_recipe_id "
                + "WHERE p.public_id = ? AND r.status = 'OPEN'", Long.class, pub.publicId())).isZero();
    }

    @Test
    void report_givenOwnPendingOrUnknownTarget_expectsRefused() {
        Published pub = community.publicRecipe(moderator, "self-report", List.of());
        assertThat(CommunityFixture.codeOf(community.report(pub.author(), "published_recipe", pub.publicId(), "mine"), 400))
                .isEqualTo("invalid_report");
        Signed reader = community.account("reader", 0, true);
        Signed author = community.author(100_00L);
        community.profile(author, "pending-only");
        Ran ran = community.recipeWithRun(author, "Pending");
        String pending = community.publish(author, ran, List.of(ran.jobId()));
        assertThat(CommunityFixture.codeOf(community.report(reader, "published_recipe", pending, "hidden"), 404))
                .isEqualTo("not_found");
        assertThat(CommunityFixture.codeOf(community.report(reader, "comment", pub.publicId(), "x"), 400))
                .isEqualTo("invalid_report");
        assertThat(CommunityFixture.codeOf(community.report(reader, "published_recipe", pub.publicId(), ""), 400))
                .isEqualTo("invalid_report");
    }

    @Test
    void report_givenMoreThanTheHourlyLimit_expects429() {
        Signed reader = community.account("busy-reader", 0, true);
        for (int i = 0; i < 3; i++) {
            Published pub = community.publicRecipe(moderator, "limit-" + i, List.of());
            community.report(reader, "published_recipe", pub.publicId(), "report " + i).expectStatus().isAccepted();
        }
        Published fourth = community.publicRecipe(moderator, "limit-4", List.of());
        assertThat(CommunityFixture.codeOf(community.report(reader, "published_recipe", fourth.publicId(), "one more"), 429))
                .isEqualTo("report_limit_reached");
    }

    // ===== Profiles under review ========================================

    @Test
    void profileChange_afterApproval_expectsQueuedAndShownOnlyOnceApproved() {
        Published pub = community.publicRecipe(moderator, "renamer", List.of());
        community.putProfile(pub.author(), pub.handle(), "New name", "https://example.test/new").expectStatus().isOk();

        assertThat(publicProfile(pub.handle())).containsEntry("display_name", "Display renamer");
        assertThat(profileItems(pub.handle())).hasSize(1);

        http.post().uri("/v1/admin/community/profiles/{handle}/approve", pub.handle())
                .header("Authorization", moderator.bearer()).exchange().expectStatus().isOk();

        assertThat(publicProfile(pub.handle())).containsEntry("display_name", "New name")
                .containsEntry("link", "https://example.test/new");
        assertThat(profileItems(pub.handle())).isEmpty();
    }

    @Test
    void reportedProfile_givenRemoval_expectsPublicNameAndLinkClearedWithTheReason() {
        Published pub = community.publicRecipe(moderator, "loud", List.of());
        Signed reader = community.account("reader", 0, true);
        community.report(reader, "profile", pub.handle(), "Impersonates a brand").expectStatus().isAccepted();
        assertThat(profileItems(pub.handle())).singleElement()
                .satisfies(item -> assertThat((List<?>) item.get("reports")).hasSize(1));

        http.post().uri("/v1/admin/community/profiles/{handle}/remove", pub.handle())
                .header("Authorization", moderator.bearer())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", "Impersonation"))
                .exchange().expectStatus().isOk();

        Map<String, Object> shown = publicProfile(pub.handle());
        assertThat(shown).containsEntry("display_name", null).containsEntry("link", null);
        assertThat(profile(pub.author())).containsEntry("review_reason", "Impersonation")
                .containsEntry("change_awaiting_review", false);
        assertThat(profileItems(pub.handle())).isEmpty();
    }

    // ===== Follows and the feed =========================================

    @Test
    void follow_givenPublicAuthor_expectsCountsAndFeedWithoutNamingFollowers() {
        Published first = community.publicRecipe(moderator, "followed", List.of());
        Signed fan = community.account("fan", 0, true);

        community.follow(fan, first.handle()).expectStatus().isNoContent();
        community.follow(fan, first.handle()).expectStatus().isNoContent();

        assertThat(following(fan)).containsExactly(first.handle());
        Map<String, Object> shown = publicProfile(first.handle());
        assertThat(((Number) shown.get("followers")).longValue()).isEqualTo(1);
        assertThat(shown.toString()).doesNotContain(fan.id().toString(), fan.email());
        Map<String, Object> recipe = http.get().uri("/v1/public/recipes/{id}", first.publicId()).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult().getResponseBody();
        assertThat(((Number) ((Map<?, ?>) recipe.get("author")).get("followers")).longValue()).isEqualTo(1);

        // The feed: the followed author's public recipes, newest first; nobody else's.
        Ran second = community.recipeWithRun(first.author(), "Second of followed");
        String secondId = community.publish(first.author(), second, List.of(second.jobId()));
        community.approve(moderator, secondId);
        community.publicRecipe(moderator, "stranger", List.of());
        assertThat(feed(fan, "")).containsExactly(secondId, first.publicId());
        assertThat(feed(fan, "?limit=1")).containsExactly(secondId);
        assertThat(feed(fan, "?limit=1&before=" + secondId)).containsExactly(first.publicId());

        // A pending recipe of the followed author is not in the feed.
        Ran third = community.recipeWithRun(first.author(), "Third, pending");
        community.publish(first.author(), third, List.of(third.jobId()));
        assertThat(feed(fan, "")).hasSize(2);

        http.delete().uri("/v1/follows/{handle}", first.handle()).header("Authorization", fan.bearer()).exchange()
                .expectStatus().isNoContent();
        assertThat(following(fan)).isEmpty();
        assertThat(feed(fan, "")).isEmpty();
        assertThat(((Number) publicProfile(first.handle()).get("followers")).longValue()).isZero();
    }

    @Test
    void follow_givenSelfOrNoPublicProfile_expectsRefused() {
        Published pub = community.publicRecipe(moderator, "selfish", List.of());
        assertThat(CommunityFixture.codeOf(community.follow(pub.author(), pub.handle()), 400)).isEqualTo("follow_self");
        Signed fan = community.account("fan", 0, true);
        Signed hidden = community.author(0);
        String hiddenHandle = community.profile(hidden, "hidden");
        assertThat(CommunityFixture.codeOf(community.follow(fan, hiddenHandle), 404)).isEqualTo("not_found");
        assertThat(CommunityFixture.codeOf(community.follow(fan, "no-such-handle"), 404)).isEqualTo("not_found");
    }

    // ===== Closing an account ===========================================

    @Test
    void closeAccount_givenAuthorWithCommunity_expectsProfileFollowsReportsAndPublishedRecipesGone() {
        Published pub = community.publicRecipe(moderator, "closing", List.of());
        Published other = community.publicRecipe(moderator, "neighbour", List.of());
        Signed fan = community.account("fan", 0, true);
        community.follow(fan, pub.handle()).expectStatus().isNoContent();
        community.follow(pub.author(), other.handle()).expectStatus().isNoContent();
        community.report(pub.author(), "published_recipe", other.publicId(), "A report by the closing account")
                .expectStatus().isAccepted();
        Signed reader = community.account("reader", 0, true);
        community.report(reader, "profile", pub.handle(), "About the closing account").expectStatus().isAccepted();

        Signed admin = community.staff(UserRole.ADMIN);
        http.post().uri("/v1/admin/users/" + pub.author().id() + "/close")
                .header("Authorization", admin.bearer()).exchange().expectStatus().isOk();

        http.get().uri("/v1/public/recipes/{id}", pub.publicId()).exchange().expectStatus().isNotFound();
        http.get().uri("/v1/public/profiles/{handle}", pub.handle()).exchange().expectStatus().isNotFound();
        assertThat(status(pub.publicId())).isEqualTo("REMOVED");
        assertThat(storage.list("community/" + pub.publicId() + "/")).isEmpty();
        UUID closed = pub.author().id();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles WHERE user_id = ?", Long.class, closed)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follows WHERE follower_id = ? OR followee_id = ?",
                Long.class, closed, closed)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE reporter_id = ?", Long.class, closed)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE profile_user_id = ? AND status = 'OPEN'",
                Long.class, closed)).isZero();
        // The handle is free again, and the other author's page is untouched.
        assertThat(following(fan)).isEmpty();
        http.get().uri("/v1/public/recipes/{id}", other.publicId()).exchange().expectStatus().isOk();
    }

    // ===== Helpers ======================================================

    private List<AuditLogEntry> events(String type, String publicId) {
        return audit.findByEventType(type).stream()
                .filter(e -> e.getEventDetail() != null && publicId.equals(e.getEventDetail().get("publicId")))
                .toList();
    }

    private String status(String publicId) {
        return jdbc.queryForObject("SELECT status FROM published_recipes WHERE public_id = ?", String.class, publicId);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> profileItems(String handle) {
        return community.queue(moderator).stream()
                .filter(item -> "profile".equals(item.get("why"))
                        && handle.equals(((Map<String, Object>) item.get("author")).get("handle")))
                .toList();
    }

    private List<String> following(Signed u) {
        return http.get().uri("/v1/follows").header("Authorization", u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<String>>() { })
                .returnResult().getResponseBody();
    }

    private List<String> feed(Signed u, String query) {
        List<Map<String, Object>> page = http.get().uri("/v1/follows/feed" + query)
                .header("Authorization", u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() { })
                .returnResult().getResponseBody();
        return page.stream().map(r -> (String) r.get("public_id")).toList();
    }
}
