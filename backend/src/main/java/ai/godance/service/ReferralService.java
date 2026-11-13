package ai.godance.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.ReferralProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.Referral;
import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.ReferralCodeRepository;
import ai.godance.repository.ReferralRepository;
import ai.godance.repository.UserRepository;

/**
 * A user's referral code and counts, and claiming someone else's code
 * (the contract in {@code docs/claude-code-briefs/round-9/00-coordination.md}).
 * The reward itself is {@link ReferralRewardService}.
 *
 * <p>Off while both reward amounts are 0 ({@link ReferralProperties#enabled}):
 * {@link #me} then says so and makes no code, and every claim is refused.
 *
 * <p>A referrer sees counts only. Nothing here returns who signed up with
 * a code, and a claim tells the invited user nothing about whose code it is.
 */
@Service
@EnableConfigurationProperties(ReferralProperties.class)
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    /** Digits and capitals without look-alikes: no 0/O, no 1/I/L. */
    static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 8;
    private static final Pattern CODE_SHAPE = Pattern.compile("[" + CODE_ALPHABET + "]{" + CODE_LENGTH + "}");
    /** A collision is one in ~10^12 per existing code; a handful of tries is plenty. */
    private static final int CODE_ATTEMPTS = 5;
    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Sources of a paid top-up: an account holding one of these is already
     * a paying customer and cannot be referred any more. Every payment rail
     * that ever credited the ledger, live or retired.
     */
    static final Set<CreditSource> PAYMENT_SOURCES = EnumSet.of(
            CreditSource.STRIPE, CreditSource.NOWPAYMENTS, CreditSource.BINANCE_PAY, CreditSource.COINBASE);

    private final ReferralProperties props;
    private final ReferralRepository referrals;
    private final ReferralCodeRepository codes;
    private final UserRepository users;
    private final CreditLedgerRepository ledgerEntries;
    private final AuditLogService audit;

    public ReferralService(ReferralProperties props, ReferralRepository referrals,
                           ReferralCodeRepository codes, UserRepository users,
                           CreditLedgerRepository ledgerEntries, AuditLogService audit) {
        this.props = props;
        this.referrals = referrals;
        this.codes = codes;
        this.users = users;
        this.ledgerEntries = ledgerEntries;
        this.audit = audit;
    }

    /** {@code GET /v1/referrals/me}: switched off, or the caller's code, the offer and counts. */
    public sealed interface MeView permits Disabled, Enabled { }

    /** The feature is off, or this account cannot take part: the app shows nothing. */
    public record Disabled() implements MeView { }

    /**
     * @param pendingRewardCents what the caller receives on a qualifying top-up
     *                           as an invited user; null when not invited, or
     *                           when that referral is already settled
     */
    public record Enabled(String code, long referrerRewardCents, long refereeRewardCents,
                          long minTopupCents, long invited, long rewarded, long earnedCents,
                          Long pendingRewardCents) implements MeView { }

    /** What a successful claim answers. */
    public record ClaimResult(long refereeRewardCents, long minTopupCents) { }

    /**
     * The caller's referral view, making their code the first time they ask.
     * Only adult accounts take part, so anyone else sees the feature off.
     */
    @Transactional
    public MeView me(UUID userId) {
        if (!props.enabled()) {
            return new Disabled();
        }
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (!u.isActive() || u.getAgeStatus() != AgeStatus.ADULT) {
            return new Disabled();
        }
        String code = codeFor(userId);
        Long pending = referrals.findByInvitedUserId(userId)
                .filter(r -> r.getStatus() == Referral.Status.PENDING)
                .map(r -> props.refereeRewardCents())
                .orElse(null);
        return new Enabled(code,
                props.referrerRewardCents(),
                props.refereeRewardCents(),
                props.minTopupCents(),
                referrals.countByReferrerId(userId),
                referrals.countByReferrerIdAndStatus(userId, Referral.Status.REWARDED),
                referrals.sumReferrerRewardCents(userId, Referral.Status.REWARDED),
                pending);
    }

    /**
     * Claim {@code rawCode} for the caller. Accepted only when the feature is
     * on and the code exists; the code is not the caller's own, its owner is
     * active and did not sign up with the caller's code; the caller signed up
     * within the claim window, has no referral and no paid top-up yet; and
     * both accounts are adults.
     *
     * @throws ReferralRefusedException 404 {@code referral_code_unknown} or
     *         409 {@code referral_not_eligible}
     */
    @Transactional
    public ClaimResult claim(UUID userId, String rawCode, String ip) {
        if (!props.enabled()) {
            throw ReferralRefusedException.notEligible("Invites aren't running right now.");
        }
        String code = normalize(rawCode);
        if (code == null) {
            throw ReferralRefusedException.unknownCode();
        }
        UUID referrerId = codes.findOwnerByCode(code).orElseThrow(ReferralRefusedException::unknownCode);
        User referrer = users.findById(referrerId).orElse(null);
        if (referrer == null || !referrer.isActive()) {
            throw ReferralRefusedException.unknownCode();
        }
        if (referrerId.equals(userId)) {
            throw ReferralRefusedException.notEligible("That's your own invite code.");
        }
        User caller = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (caller.getAgeStatus() != AgeStatus.ADULT || referrer.getAgeStatus() != AgeStatus.ADULT) {
            throw ReferralRefusedException.notEligible(
                    "Invites are for people " + ParentalConsentService.CONSENT_AGE + " or older.");
        }
        Instant now = Instant.now();
        if (caller.getCreatedAt() == null || caller.getCreatedAt().isBefore(now.minus(props.claimWindow()))) {
            throw ReferralRefusedException.notEligible("An invite code can only be used in the first "
                    + describe(props.claimWindow()) + " after signing up.");
        }
        if (referrals.existsByInvitedUserId(userId)) {
            throw ReferralRefusedException.notEligible("This account has already used an invite code.");
        }
        if (ledgerEntries.existsByUserIdAndSourceIn(userId, PAYMENT_SOURCES)) {
            throw ReferralRefusedException.notEligible("Invite codes are for accounts that haven't topped up yet.");
        }
        if (referrals.existsByReferrerIdAndInvitedUserId(userId, referrerId)) {
            // Two accounts inviting each other would each earn both rewards.
            throw ReferralRefusedException.notEligible("You invited the owner of this code, so it can't be used here.");
        }
        Referral referral = new Referral(referrerId, userId, now);
        try {
            referrals.saveAndFlush(referral);
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // Another claim for this account committed first.
            log.info("referral claim for user {} lost a race to another claim", userId);
            throw ReferralRefusedException.notEligible("This account has already used an invite code.");
        }
        audit.record(AuditLogService.EVT_REFERRAL_CLAIMED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("referralId", referral.getId().toString()));
        return new ClaimResult(props.refereeRewardCents(), props.minTopupCents());
    }

    /**
     * The user's code, made now if they have none. Inside the caller's
     * transaction; a concurrent first ask for the same user gets the same code.
     */
    private String codeFor(UUID userId) {
        Optional<String> existing = codes.findCodeByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            codes.insertIfAbsent(userId, randomCode());
            // Ours, or the one a concurrent request wrote first. Empty only
            // when the random code belonged to someone else: try another.
            Optional<String> mine = codes.findCodeByUserId(userId);
            if (mine.isPresent()) {
                return mine.get();
            }
        }
        throw new IllegalStateException("could not make a unique referral code for user " + userId);
    }

    static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RNG.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** The code in canonical form, or null if it cannot be one. Lower case is accepted. */
    static String normalize(String raw) {
        if (raw == null) return null;
        String code = raw.strip().toUpperCase(Locale.ROOT);
        return CODE_SHAPE.matcher(code).matches() ? code : null;
    }

    private static String describe(Duration window) {
        long hours = window.toHours();
        if (hours > 0 && hours % 24 == 0) {
            long days = hours / 24;
            return days + (days == 1 ? " day" : " days");
        }
        return hours + (hours == 1 ? " hour" : " hours");
    }
}
