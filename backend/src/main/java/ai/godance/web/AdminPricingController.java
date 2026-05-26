package ai.godance.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ProposalStatus;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.service.pricing.ProposalApprovalService;
import reactor.core.publisher.Mono;

/**
 * Operator-only API for the pricing staging lane. Reachable only via
 * an authenticated request whose JWT {@code email} matches the
 * {@code godance.admin.emails} allowlist — see
 * {@link ai.godance.security.AdminAuthorizationFilter}.
 *
 * <p>This is a v1 surface: no UI, no pagination, no filtering beyond
 * {@code status}. The next pass (frontend consolidation) wraps it.
 */
@RestController
@RequestMapping("/v1/admin/pricing")
public class AdminPricingController {

    private final ModelChangeProposalRepository proposals;
    private final ProposalApprovalService approval;

    public AdminPricingController(ModelChangeProposalRepository proposals,
                                  ProposalApprovalService approval) {
        this.proposals = proposals;
        this.approval = approval;
    }

    @GetMapping("/proposals")
    public Mono<ProposalListResponse> list(@RequestParam(value = "status", required = false) String status) {
        List<ModelChangeProposal> rows = status == null || status.isBlank()
                ? proposals.findAllByOrderByDetectedAtDesc()
                : proposals.findByStatusOrderByDetectedAtDesc(parseStatus(status));
        return Mono.just(new ProposalListResponse(rows.stream().map(AdminPricingController::toView).toList()));
    }

    @PostMapping("/proposals/{id}/approve")
    public Mono<ProposalView> approve(@PathVariable("id") Long id, @AuthenticationPrincipal Jwt jwt) {
        return Mono.fromCallable(() -> approval.approve(id, reviewerFromJwt(jwt))).map(AdminPricingController::toView);
    }

    @PostMapping("/proposals/{id}/reject")
    public Mono<ProposalView> reject(@PathVariable("id") Long id, @AuthenticationPrincipal Jwt jwt) {
        return Mono.fromCallable(() -> approval.reject(id, reviewerFromJwt(jwt))).map(AdminPricingController::toView);
    }

    private static String reviewerFromJwt(Jwt jwt) {
        if (jwt == null) return null;
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getSubject();
    }

    private static ProposalStatus parseStatus(String raw) {
        try {
            return ProposalStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be one of PENDING, APPROVED, REJECTED, ANOMALY");
        }
    }

    private static ProposalView toView(ModelChangeProposal p) {
        return new ProposalView(
                p.getId(),
                p.getType().name(),
                p.getStatus().name(),
                p.getModelName(),
                p.getProvider() == null ? null : p.getProvider().name(),
                p.getModality() == null ? null : p.getModality().name(),
                p.getPayload(),
                p.getSource(),
                p.getSourceUrl(),
                p.getDetectedAt(),
                p.getReviewedBy(),
                p.getReviewedAt());
    }

    public record ProposalListResponse(List<ProposalView> proposals) { }

    public record ProposalView(
            Long id,
            String type,
            String status,
            String modelName,
            String provider,
            String modality,
            Map<String, Object> payload,
            String source,
            String sourceUrl,
            Instant detectedAt,
            String reviewedBy,
            Instant reviewedAt
    ) { }
}
