package ai.godance.service.pricing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CatalogStatus;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.PriceField;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.repository.ModelPriceHistoryRepository;

/**
 * End-to-end: WireMock-served pricing fixtures → {@link
 * PricingRefreshService#runOnce()} → assertions on the staging-lane
 * tables. Covers diff (match / within-noise / above-noise), discovery
 * (NEW_VERSION / NEW_MODEL), the always-write history invariant, and
 * the "never mutates a live billable price" promise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class PricingRefreshFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer feeds;

    @BeforeAll
    static void startFeeds() throws IOException {
        feeds = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        feeds.start();
        feeds.stubFor(get(urlPathEqualTo("/api/v1/models"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(Files.readString(Path.of(
                                "src/test/resources/pricing-feed-fixtures/openrouter-models.json")))));
        feeds.stubFor(get(urlPathEqualTo("/litellm.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(Files.readString(Path.of(
                                "src/test/resources/pricing-feed-fixtures/litellm-prices.json")))));
    }

    @AfterAll
    static void stopFeeds() {
        if (feeds != null) feeds.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.pricing.feeds.openrouter-url", () -> feeds.baseUrl() + "/api/v1/models");
        r.add("godance.pricing.feeds.litellm-url",    () -> feeds.baseUrl() + "/litellm.json");
        // The alias map for fixture entries lives in src/main/resources/application.yml
        // (Spring's relaxed binding can't reliably express map keys with '/' via
        // @DynamicPropertySource), so the test relies on that prod-yml mapping.
        // Push the daily cron 75 years out so the Quartz trigger registered by
        // QuartzJobsConfig never auto-fires during the test.
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired PricingRefreshService refresh;
    @Autowired ModelCatalogEntryRepository catalog;
    @Autowired ModelChangeProposalRepository proposals;
    @Autowired ModelPriceHistoryRepository history;

    @Test
    void runOnce_givenStubbedFeeds_writesHistoryAndStagesProposalsWithoutMutatingCatalog() {
        // Snapshot the live billable values up-front; nothing under test should change these.
        long opusInputBefore = catalog.findByModelName("claude-opus-4-7").orElseThrow().getInputMicrocentsPer1k();
        long deepseekInputBefore = catalog.findByModelName("deepseek-v4-pro").orElseThrow().getInputMicrocentsPer1k();

        PricingRefreshService.Digest d = refresh.runOnce();

        // (a) History rows exist for every model we observed — always-write contract.
        assertThat(history.findByModelNameOrderByObservedAtDesc("claude-opus-4-7")).isNotEmpty();
        assertThat(history.findByModelNameOrderByObservedAtDesc("deepseek-v4-pro")).isNotEmpty();

        // (b) claude-opus-4-7 matches catalog exactly → no proposal.
        assertThat(proposalsFor("claude-opus-4-7", ProposalType.PRICE_CHANGE)).isEmpty();

        // (c) deepseek-v4-pro is above-noise (1740 → 2000 µ¢/1k, ~15%) → PRICE_CHANGE.
        List<ModelChangeProposal> deepseekProposals = proposalsFor("deepseek-v4-pro", ProposalType.PRICE_CHANGE);
        assertThat(deepseekProposals).isNotEmpty();
        ModelChangeProposal deepseekInput = deepseekProposals.stream()
                .filter(p -> PriceField.INPUT.asColumnKey().equals(p.getPayload().get("field")))
                .findFirst().orElseThrow();
        assertThat(deepseekInput.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(((Number) deepseekInput.getPayload().get("new_value")).longValue()).isEqualTo(2_000L);

        // (d) gpt-5.5 input delta is 0.8% (≤1% noise threshold) → no PRICE_CHANGE proposal.
        long gpt55Input = proposalsFor("gpt-5.5", ProposalType.PRICE_CHANGE).stream()
                .filter(p -> PriceField.INPUT.asColumnKey().equals(p.getPayload().get("field")))
                .count();
        assertThat(gpt55Input).isZero();

        // (e) Discovery: claude-opus-4-8 not in catalog, family-stem match → NEW_VERSION.
        assertThat(proposalsFor("claude-opus-4-8", ProposalType.NEW_VERSION)).isNotEmpty();
        // (f) xai/grok-mythos-5-0 — brand new id, no family → NEW_MODEL.
        assertThat(proposalsFor("grok-mythos-5-0", ProposalType.NEW_MODEL)).isNotEmpty();

        // (g) The cardinal rule — live billable values are unchanged.
        assertThat(catalog.findByModelName("claude-opus-4-7").orElseThrow().getInputMicrocentsPer1k())
                .isEqualTo(opusInputBefore);
        assertThat(catalog.findByModelName("deepseek-v4-pro").orElseThrow().getInputMicrocentsPer1k())
                .isEqualTo(deepseekInputBefore);

        // (h) Digest reflects the matrix.
        assertThat(d.priceChanges).isGreaterThanOrEqualTo(1);
        assertThat(d.newVersions).isGreaterThanOrEqualTo(1);
        assertThat(d.newModels).isGreaterThanOrEqualTo(1);
        assertThat(d.errors).isZero();
    }

    @Test
    void approveDiscoveryProposal_landsAsNonBillableDiscoveredRow() {
        refresh.runOnce();
        var newModelProposal = proposalsFor("grok-mythos-5-0", ProposalType.NEW_MODEL).get(0);
        // We don't go through the controller here (admin auth is covered separately);
        // direct service call is sufficient to verify the NEW_MODEL → DISCOVERED rule.
        ProposalApprovalService approval = approvalFor();
        approval.approve(newModelProposal.getId(), "test@godance.local");

        ModelCatalogEntry row = catalog.findByModelName("grok-mythos-5-0").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(CatalogStatus.DISCOVERED);
    }

    private ProposalApprovalService approvalFor() {
        // Local helper that goes through Spring instead of constructing by hand,
        // so the in-memory cache refresh and audit log path are exercised.
        return springApprovalService;
    }

    @Autowired ProposalApprovalService springApprovalService;

    private List<ModelChangeProposal> proposalsFor(String modelName, ProposalType type) {
        return proposals.findAllByOrderByDetectedAtDesc().stream()
                .filter(p -> modelName.equals(p.getModelName()) && p.getType() == type)
                .toList();
    }
}
