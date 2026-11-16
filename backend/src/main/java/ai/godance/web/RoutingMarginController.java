package ai.godance.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.UserRole;
import ai.godance.routing.RoutingMarginService;
import ai.godance.security.AdminRoles;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The routing margin view for finance (round 4's Phase 2, round 14 brief 04):
 * per routing policy and rule, served requests, what callers paid, what the
 * served models cost, and the spread. Under {@code /v1/admin/**}, so
 * {@code AdminAuthorizationFilter} admits only the roles below, with a
 * verified email and two-factor on. Left out of the public API document, as
 * every admin route is.
 */
@RestController
@RequestMapping("/v1/admin/routing")
public class RoutingMarginController {

    private final RoutingMarginService margins;

    public RoutingMarginController(RoutingMarginService margins) {
        this.margins = margins;
    }

    /** The usage summary's range: {@code from} and {@code to} inclusive (UTC), the last 30 days by default. */
    @GetMapping("/margins")
    @AdminRoles({UserRole.FINANCE, UserRole.ADMIN})
    public Mono<RoutingMarginService.Margins> margins(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        LocalDate toDate = parseDate(to, LocalDate.now(ZoneOffset.UTC));
        LocalDate fromDate = parseDate(from, toDate.minusDays(29));
        return Mono.fromCallable(() -> margins.margins(fromDate, toDate))
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
}
