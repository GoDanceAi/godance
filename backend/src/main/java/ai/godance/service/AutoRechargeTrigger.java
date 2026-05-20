package ai.godance.service;

import java.util.Date;
import java.util.UUID;

import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
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

import ai.godance.domain.User;
import ai.godance.repository.UserRepository;

/**
 * Debit-driven entry point into the auto-recharge loop. Called by
 * {@link CreditLedgerService} after every successful balance decrement;
 * if the user's auto-recharge is enabled AND their new balance is below
 * the configured threshold, enqueues a Quartz one-shot job that will
 * call {@link AutoRechargeService#chargeIfNeeded} on a scheduler thread.
 *
 * <p>Why Quartz vs inline async: the job is persisted, so a JVM crash
 * between the debit commit and the off-session charge doesn't lose the
 * pending recharge — Quartz picks it up on next startup.
 *
 * <p>Why a separate bean (vs. CreditLedgerService calling Quartz
 * directly): keeps the ledger layer free of scheduler concerns, and
 * lets us drop {@code @ConditionalOnProperty} or a no-op stub here in
 * tests/profiles that don't want the side effect.
 */
@Service
public class AutoRechargeTrigger {

    /** Quartz job-group for all debit-driven one-shots. */
    public static final String GROUP_DEBIT_TRIGGERED = "autorecharge-debit";

    private static final Logger log = LoggerFactory.getLogger(AutoRechargeTrigger.class);

    private final UserRepository users;
    private final Scheduler scheduler;

    public AutoRechargeTrigger(UserRepository users, Scheduler scheduler) {
        this.users = users;
        this.scheduler = scheduler;
    }

    /**
     * Inspect the user's auto-recharge state. If eligible + below threshold,
     * enqueue a fire-now Quartz job. Returns whether a job was scheduled.
     *
     * <p>Idempotent on the Quartz side: if a job for this user is already
     * pending in the {@link #GROUP_DEBIT_TRIGGERED} group, we skip — the
     * existing job will pick up the latest balance when it fires.
     */
    public boolean maybeEnqueue(UUID userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null || !u.isAutoRechargeEnabled())  return false;
        if (u.getAutoRechargeThresholdCents() == null) return false;
        if (u.getCreditBalanceCents() >= u.getAutoRechargeThresholdCents()) return false;

        JobKey key = new JobKey("autorecharge-" + userId, GROUP_DEBIT_TRIGGERED);
        try {
            if (scheduler.checkExists(key)) {
                // A previous trigger for this user is still pending; let it run.
                return false;
            }
            JobDataMap data = new JobDataMap();
            data.put(AutoRechargeJob.DATA_USER_ID, userId.toString());

            JobDetail job = JobBuilder.newJob(AutoRechargeJob.class)
                    .withIdentity(key)
                    .usingJobData(data)
                    .storeDurably(false)
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trig-" + userId, GROUP_DEBIT_TRIGGERED)
                    .forJob(job)
                    .startAt(new Date())                       // fire ASAP
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withRepeatCount(0)
                            .withMisfireHandlingInstructionFireNow())
                    .build();

            scheduler.scheduleJob(job, trigger);
            return true;
        } catch (SchedulerException e) {
            log.warn("could not enqueue auto-recharge job for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
