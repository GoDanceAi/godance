package ai.godance.service;

import java.util.List;
import java.util.UUID;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.PersistJobDataAfterExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ai.godance.repository.UserRepository;

/**
 * Daily safety-net: scans for users whose balance is below their
 * auto-recharge threshold and weren't caught by the debit-driven path
 * (e.g., transient Stripe error, app process crashed mid-charge, the
 * debit-trigger lost the race against a concurrent top-up). Calls
 * {@link AutoRechargeService#chargeIfNeeded} for each. Bound to a cron
 * trigger in {@link ai.godance.config.QuartzJobsConfig}.
 *
 * <p>{@link DisallowConcurrentExecution} prevents overlapping runs if a
 * single iteration takes longer than the cron period (it shouldn't, but
 * it's cheap insurance).
 */
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
public class AutoRechargeCronJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AutoRechargeCronJob.class);

    @Autowired private UserRepository users;
    @Autowired private AutoRechargeService autoRecharge;

    @Override
    public void execute(JobExecutionContext context) {
        List<UUID> candidates = users.findAutoRechargeCandidates();
        if (candidates.isEmpty()) {
            log.debug("AutoRechargeCronJob: no candidates");
            return;
        }
        int charged = 0, skipped = 0, failed = 0;
        for (UUID userId : candidates) {
            switch (autoRecharge.chargeIfNeeded(userId)) {
                case CHARGED               -> charged++;
                case HARD_FAIL_DISABLED    -> failed++;
                default                    -> skipped++;
            }
        }
        log.info("AutoRechargeCronJob: candidates={} charged={} skipped={} failed={}",
                candidates.size(), charged, skipped, failed);
    }
}
