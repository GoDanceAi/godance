package ai.godance.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.security.JwtService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code GET /v1/usage?limit=N} — recent usage records for the caller,
 * newest first. Backs the dashboard "history" surface.
 */
@RestController
@RequestMapping("/v1")
public class UsageController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;

    private final UsageRecordRepository usage;

    public UsageController(UsageRecordRepository usage) {
        this.usage = usage;
    }

    @GetMapping("/usage")
    public Mono<List<UsageView>> recent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return Mono.fromCallable(() -> usage
                        .findByUserIdOrderByRecordedAtDesc(userId, PageRequest.of(0, safeLimit))
                        .stream().map(UsageView::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record UsageView(
            UUID id,
            Modality modality,
            Provider provider,
            String model,
            long inputTokens,
            long outputTokens,
            long costCents,
            Long providerCostCents,
            Region region,
            boolean cacheHit,
            boolean fallbackUsed,
            String requestId,
            Instant recordedAt
    ) {
        public static UsageView from(UsageRecord r) {
            return new UsageView(r.getId(), r.getModality(), r.getProvider(), r.getModelName(),
                    r.getInputTokens(), r.getOutputTokens(), r.getCostCents(),
                    r.getProviderCostCents(), r.getRegion(),
                    r.isCacheHit(), r.isFallbackUsed(), r.getRequestId(), r.getRecordedAt());
        }
    }
}
