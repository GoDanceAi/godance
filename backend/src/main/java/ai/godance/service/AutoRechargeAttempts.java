package ai.godance.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.AutoRechargeAttempt;
import ai.godance.repository.AutoRechargeAttemptRepository;

/**
 * Commits a new auto-recharge attempt in its own transaction, before the
 * charge it stands for is sent, so the record survives whatever happens to
 * the caller's transaction afterwards (spec risk R-04).
 */
@Component
public class AutoRechargeAttempts {

    private final AutoRechargeAttemptRepository attempts;

    public AutoRechargeAttempts(AutoRechargeAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * A new PENDING attempt, committed. Throws
     * {@link org.springframework.dao.DataIntegrityViolationException} when the
     * user already has one pending: another run got there first, and the
     * database allows one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRechargeAttempt begin(UUID userId, long amountCents) {
        AutoRechargeAttempt attempt = new AutoRechargeAttempt();
        attempt.setUserId(userId);
        attempt.setAmountCents(amountCents);
        attempt.setStatus(AutoRechargeAttempt.Status.PENDING);
        return attempts.saveAndFlush(attempt);
    }

    /**
     * Marks a PENDING attempt FAILED in its own transaction, so a new attempt
     * for the same user can be committed right after it (the database allows
     * one pending attempt per user).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retire(UUID attemptId, String reason) {
        attempts.findById(attemptId).ifPresent(a -> {
            if (a.getStatus() != AutoRechargeAttempt.Status.PENDING) return;
            a.setStatus(AutoRechargeAttempt.Status.FAILED);
            a.setFailureReason(reason);
            a.setResolvedAt(Instant.now());
            attempts.save(a);
        });
    }
}
