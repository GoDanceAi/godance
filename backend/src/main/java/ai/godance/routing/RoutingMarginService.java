package ai.godance.routing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.repository.UsageRecordRepository;

/**
 * Round 4's Phase 2 margin view (round 14, brief 04): per routing policy and
 * rule over a range of days, how many routed requests were served, what their
 * callers paid, what the served models cost, and the spread between the two.
 * Read from the {@code routed_policy} and {@code routed_rule} columns (V41)
 * that every routed request's usage records carry, so traffic from before V41
 * and direct model calls are not in it.
 *
 * <p>Money is in integer cents: each row sums exact microcents and rounds
 * once, as the admin usage summary does, and the spread and the total are
 * taken from those rounded cents, so every row and the total add up.
 */
@Service
public class RoutingMarginService {

    private final UsageRecordRepository usage;

    public RoutingMarginService(UsageRecordRepository usage) {
        this.usage = usage;
    }

    /** One routing policy's rule over the range. */
    public record MarginRow(
            @JsonProperty("policy")          String policy,
            @JsonProperty("rule")            String rule,
            @JsonProperty("requests")        long requests,
            @JsonProperty("failed_attempts") long failedAttempts,
            @JsonProperty("paid_cents")      long paidCents,
            @JsonProperty("cost_cents")      long costCents,
            @JsonProperty("spread_cents")    long spreadCents
    ) { }

    /** Every row added up. */
    public record MarginTotal(
            @JsonProperty("requests")        long requests,
            @JsonProperty("failed_attempts") long failedAttempts,
            @JsonProperty("paid_cents")      long paidCents,
            @JsonProperty("cost_cents")      long costCents,
            @JsonProperty("spread_cents")    long spreadCents
    ) { }

    public record Margins(
            @JsonProperty("from")  LocalDate from,
            @JsonProperty("to")    LocalDate to,
            @JsonProperty("rows")  List<MarginRow> rows,
            @JsonProperty("total") MarginTotal total
    ) { }

    /**
     * Between {@code from} and {@code to}, inclusive calendar days in UTC, with
     * the usage summary's limits: {@code from} not after {@code to}, at most
     * about a year.
     */
    @Transactional(readOnly = true)
    public Margins margins(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be <= to");
        }
        if (from.plusDays(370).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window too large (max ~1 year)");
        }
        Instant fromTs = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<MarginRow> rows = usage.routingMargins(fromTs, toTs).stream()
                .map(r -> new MarginRow(r.getPolicy(), r.getRule(), r.getRequests(), r.getFailedAttempts(),
                        r.getPaidCents(), r.getCostCents(), r.getPaidCents() - r.getCostCents()))
                .toList();
        MarginTotal total = new MarginTotal(
                rows.stream().mapToLong(MarginRow::requests).sum(),
                rows.stream().mapToLong(MarginRow::failedAttempts).sum(),
                rows.stream().mapToLong(MarginRow::paidCents).sum(),
                rows.stream().mapToLong(MarginRow::costCents).sum(),
                rows.stream().mapToLong(MarginRow::spreadCents).sum());
        return new Margins(from, to, rows, total);
    }
}
