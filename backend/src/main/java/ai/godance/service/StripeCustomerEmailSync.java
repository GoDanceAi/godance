package ai.godance.service;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;

/**
 * Keeps a user's Stripe customer on the account's current email. Stripe
 * sends receipts to the customer's address and Checkout prefills it, so
 * after a change the old address would otherwise keep getting them. After
 * a release (see {@link AuthService#releaseEmail}) that address is a
 * stranger's.
 *
 * <p>The push runs as a Quartz job enqueued after the change commits, the
 * same way as {@link AutoRechargeTrigger}: the request doesn't wait on
 * Stripe, and the job is persisted, so a restart doesn't lose it. The job
 * reads the email when it fires, so a later change can't be overwritten by
 * an earlier one. While Stripe is unreachable, rate-limits or errors on
 * its side, it tries again every {@link #RETRY_INTERVAL_MINUTES} minutes,
 * {@link #MAX_ATTEMPTS} times in all.
 */
@Service
public class StripeCustomerEmailSync {

    /** Quartz job-group for the email pushes. */
    public static final String GROUP = "stripe-customer-email";

    static final int MAX_ATTEMPTS = 8;
    static final int RETRY_INTERVAL_MINUTES = 15;

    private static final Logger log = LoggerFactory.getLogger(StripeCustomerEmailSync.class);

    private final UserRepository users;
    private final StripeService stripe;
    private final AuditLogService audit;
    private final Scheduler scheduler;

    public StripeCustomerEmailSync(UserRepository users, StripeService stripe,
                                   AuditLogService audit, Scheduler scheduler) {
        this.users = users;
        this.stripe = stripe;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    /**
     * Call after changing {@code user}'s email. Once the surrounding
     * transaction commits, enqueues a push to the user's Stripe customer.
     * Does nothing for a user without one.
     */
    public void syncAfterCommit(User user) {
        if (user.getStripeCustomerId() == null) return;
        UUID userId = user.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    enqueue(userId);
                }
            });
        } else {
            enqueue(userId);
        }
    }

    /**
     * One job per change, never replacing a pending one: a job that is
     * already running may have read the older email.
     */
    void enqueue(UUID userId) {
        JobKey key = new JobKey("stripe-email-" + userId + "-" + UUID.randomUUID(), GROUP);
        JobDetail job = JobBuilder.newJob(StripeCustomerEmailJob.class)
                .withIdentity(key)
                .usingJobData(StripeCustomerEmailJob.DATA_USER_ID, userId.toString())
                .storeDurably(false)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(key.getName(), GROUP)
                .forJob(job)
                .startAt(new Date())
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(RETRY_INTERVAL_MINUTES)
                        .withRepeatCount(MAX_ATTEMPTS - 1)
                        .withMisfireHandlingInstructionNowWithExistingCount())
                .build();
        try {
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException e) {
            log.warn("could not enqueue Stripe customer email sync for user {}: {}",
                    userId, e.getMessage());
        }
    }

    /**
     * Push the user's current email to their Stripe customer, clearing it
     * for a placeholder address. Returns false only when the attempt should
     * be repeated: Stripe failed in a way that may pass, and this was not
     * the last attempt.
     */
    public boolean push(UUID userId, boolean lastAttempt) {
        User u = users.findById(userId).orElse(null);
        if (u == null || u.getStripeCustomerId() == null) return true;
        String customerId = u.getStripeCustomerId();
        try {
            stripe.updateCustomerEmail(customerId, StripeService.customerEmail(u));
        } catch (StripeException e) {
            if (isRetryable(e) && !lastAttempt) {
                log.info("Stripe customer {} email update failed, will retry: {}",
                        customerId, e.getMessage());
                return false;
            }
            log.warn("Stripe customer {} email update failed, giving up: {}", customerId, e.getMessage());
            audit.record(AuditLogService.EVT_BILLING_CUSTOMER_EMAIL_SYNC, AuditOutcome.FAILURE,
                    userId, null, Map.of("customerId", customerId, "error", String.valueOf(e.getMessage())));
            return true;
        }
        audit.record(AuditLogService.EVT_BILLING_CUSTOMER_EMAIL_SYNC, AuditOutcome.SUCCESS,
                userId, null, Map.of("customerId", customerId));
        return true;
    }

    /**
     * No response at all, a rate limit, or a Stripe-side error. A 4xx such
     * as a customer deleted in the dashboard fails the same way every time.
     */
    static boolean isRetryable(StripeException e) {
        // The SDK gives a connection failure status 0, not null.
        if (e instanceof ApiConnectionException) return true;
        Integer status = e.getStatusCode();
        return status == null || status == 429 || status >= 500;
    }
}
