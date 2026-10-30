package ai.godance.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.UserRole;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.security.AdminAuthorizationFilter;
import ai.godance.security.AdminRoles;
import ai.godance.service.admin.AdminCatalogService;
import ai.godance.service.pricing.ProposalApprovalService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Staff API for the pricing staging lane. Reachable only via an
 * authenticated session whose account has a verified email and a role the
 * route declares with {@link AdminRoles} — see
 * {@link AdminAuthorizationFilter}, which also supplies that email as
 * the reviewer recorded on approvals and catalog edits.
 *
 * <p>This is a v1 surface: no UI, no pagination, no filtering beyond
 * {@code status}. The next pass (frontend consolidation) wraps it.
 */
@RestController
@RequestMapping("/v1/admin/pricing")
public class AdminPricingController {

    private final ModelChangeProposalRepository proposals;
    private final ProposalApprovalService approval;
    private final AdminCatalogService catalog;

    public AdminPricingController(ModelChangeProposalRepository proposals,
                                  ProposalApprovalService approval,
                                  AdminCatalogService catalog) {
        this.proposals = proposals;
        this.approval = approval;
        this.catalog = catalog;
    }

    @GetMapping("/proposals")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<ProposalListResponse> list(@RequestParam(value = "status", required = false) String status) {
        List<ModelChangeProposal> rows = status == null || status.isBlank()
                ? proposals.findAllByOrderByDetectedAtDesc()
                : proposals.findByStatusOrderByDetectedAtDesc(parseStatus(status));
        return Mono.just(new ProposalListResponse(rows.stream().map(AdminPricingController::toView).toList()));
    }

    @PostMapping("/proposals/{id}/approve")
    @AdminRoles(UserRole.ADMIN)
    public Mono<ProposalView> approve(@PathVariable("id") Long id,
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE) String reviewer) {
        return Mono.fromCallable(() -> approval.approve(id, reviewer)).map(AdminPricingController::toView);
    }

    @PostMapping("/proposals/{id}/reject")
    @AdminRoles(UserRole.ADMIN)
    public Mono<ProposalView> reject(@PathVariable("id") Long id,
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE) String reviewer) {
        return Mono.fromCallable(() -> approval.reject(id, reviewer)).map(AdminPricingController::toView);
    }

    // ===== Catalog management (admin console, round 5) =================

    @GetMapping("/models")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<CatalogListResponse> listModels() {
        return Mono.fromCallable(() -> new CatalogListResponse(catalog.listModels()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/models/{id}")
    @AdminRoles(UserRole.ADMIN)
    public Mono<AdminCatalogService.CatalogModelView> updateModel(
            @PathVariable("id") Long id,
            @RequestBody ModelUpdateRequest body,
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE) String reviewer) {
        return Mono.fromCallable(() ->
                        catalog.updateModel(id, body.enabled(), body.markupPercent(), reviewer))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/models/{id}/history")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<PriceHistoryResponse> priceHistory(@PathVariable("id") Long id) {
        return Mono.fromCallable(() -> new PriceHistoryResponse(catalog.priceHistory(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record CatalogListResponse(List<AdminCatalogService.CatalogModelView> models) { }

    public record ModelUpdateRequest(Boolean enabled, Double markupPercent) { }

    public record PriceHistoryResponse(List<AdminCatalogService.PriceHistoryView> history) { }

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
