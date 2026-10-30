package ai.godance.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.UserRole;
import ai.godance.security.AdminAuthorizationFilter;
import ai.godance.security.AdminRoles;
import ai.godance.service.admin.AdminConsoleService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Admin console: user lookup / credit adjustment / revenue-vs-cost
 * overview, plus the {@code /me} probe the SPA uses to decide what to
 * render. Lives under {@code /v1/admin/**} so
 * {@link AdminAuthorizationFilter} gates every route on its
 * {@link AdminRoles} — reaching a handler here implies the caller's account
 * has a verified email and a declared role, and the filter hands over the
 * email as the {@link AdminAuthorizationFilter#ADMIN_EMAIL_ATTRIBUTE}
 * request attribute.
 *
 * <p>JPA calls are blocking, so they hop to {@code boundedElastic}
 * rather than running on the Netty event loop.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminConsoleController {

    private final AdminConsoleService console;

    public AdminConsoleController(AdminConsoleService console) {
        this.console = console;
    }

    /**
     * Reachable ⇒ the caller passed the role filter, so this simply echoes
     * who they are and what they hold. Customers get the filter's 403 and
     * the SPA hides the Admin nav.
     */
    @GetMapping("/me")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<AdminMeResponse> me(
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE) String adminEmail,
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_ROLE_ATTRIBUTE) UserRole role) {
        return Mono.just(new AdminMeResponse(adminEmail, role));
    }

    @GetMapping("/users")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<UserSearchResponse> searchUsers(@RequestParam(value = "q", required = false) String q) {
        return Mono.fromCallable(() -> new UserSearchResponse(console.searchUsers(q)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/users/{id}/ledger")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<AdminConsoleService.LedgerPageResponse> userLedger(
            @PathVariable("id") UUID id,
            @RequestParam(value = "page", defaultValue = "0") int page) {
        return Mono.fromCallable(() -> console.userLedger(id, page))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/users/{id}/credit-adjust")
    @AdminRoles({UserRole.FINANCE, UserRole.ADMIN})
    public Mono<AdminConsoleService.AdjustResult> creditAdjust(
            @PathVariable("id") UUID id,
            @RequestBody CreditAdjustRequest body,
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE) String adminEmail) {
        return Mono.fromCallable(() -> console.adjustCredit(
                        id, body.amountCents(), body.note(), body.idempotencyKey(), adminEmail))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/usage/summary")
    @AdminRoles({UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN})
    public Mono<AdminConsoleService.UsageSummary> usageSummary(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "groupBy", defaultValue = "day") String groupBy) {
        if (!"day".equals(groupBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy must be 'day'");
        }
        LocalDate toDate = parseDate(to, LocalDate.now(java.time.ZoneOffset.UTC));
        LocalDate fromDate = parseDate(from, toDate.minusDays(29));
        return Mono.fromCallable(() -> console.usageSummary(fromDate, toDate))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dates must be ISO format (yyyy-MM-dd)");
        }
    }

    public record AdminMeResponse(String email, UserRole role) { }

    public record UserSearchResponse(List<AdminConsoleService.UserView> users) { }

    public record CreditAdjustRequest(long amountCents, String note, String idempotencyKey) { }
}
