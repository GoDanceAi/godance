package ai.godance.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ai.godance.domain.Modality;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.PriceField;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.domain.Provider;
import ai.godance.repository.ModelChangeProposalRepository;
import reactor.core.publisher.Mono;

/**
 * Replaces the no-op {@link LlmFallbackExtractor} with a stub that
 * emits one synthetic {@link ObservedPrice} carrying a source URL.
 * Asserts that the observation flows through {@link PricingRefreshService}
 * and lands as a {@link ProposalType#NEW_MODEL} proposal with the
 * source URL preserved in the row — the "page → LLM → proposal" path
 * the brief calls out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Import(LlmFallbackProposalIntegrationTest.StubExtractorConfig.class)
class LlmFallbackProposalIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // Aim the live feed URLs at a definitely-unreachable port so the
        // run doesn't depend on outbound network; the feed clients log
        // and swallow the error, leaving the LLM-fallback the only
        // emitter for this test.
        r.add("godance.pricing.feeds.openrouter-url", () -> "http://127.0.0.1:1/openrouter");
        r.add("godance.pricing.feeds.litellm-url",    () -> "http://127.0.0.1:1/litellm");
        r.add("godance.pricing.refresh-cron",         () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired PricingRefreshService refresh;
    @Autowired ModelChangeProposalRepository proposals;

    @Test
    void runOnce_givenStubExtractorEmission_writesNewModelProposalWithSourceUrl() {
        refresh.runOnce();

        List<ModelChangeProposal> fallbackProposals = proposals.findAllByOrderByDetectedAtDesc().stream()
                .filter(p -> "llm_fallback".equals(p.getSource()))
                .toList();

        assertThat(fallbackProposals).hasSize(1);
        ModelChangeProposal p = fallbackProposals.get(0);
        assertThat(p.getType()).isEqualTo(ProposalType.NEW_MODEL);
        assertThat(p.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(p.getModelName()).isEqualTo("ernie-9.0-mythos");
        assertThat(p.getProvider()).isEqualTo(Provider.BAIDU);
        assertThat(p.getSourceUrl()).isEqualTo("https://qianfan.cloud.baidu.com/pricing");
        assertThat(p.getPayload()).containsEntry("source", "llm_fallback");
    }

    @TestConfiguration
    static class StubExtractorConfig {
        @Bean
        @Primary
        LlmFallbackExtractor stubExtractor() {
            return () -> Mono.just(List.of(new ObservedPrice(
                    "ernie-9.0-mythos",      // sourceModelId — fresh model, never seen by aggregators
                    "ernie-9.0-mythos",      // godanceModelName (resolver will reclassify as NEW_MODEL)
                    Provider.BAIDU,
                    Modality.TEXT,
                    PriceField.INPUT,
                    600L,                     // 600 microcents/1k — within sanity bounds
                    new BigDecimal("0.0000006"),
                    "llm_fallback",
                    "https://qianfan.cloud.baidu.com/pricing",
                    Instant.now())));
        }
    }
}
