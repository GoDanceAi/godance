package ai.godance.service;

import java.util.UUID;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job that runs {@link StripeCustomerEmailSync#push} for one user id
 * passed in via the job's data map under {@code userId}. Its trigger
 * repeats for retries; once a push is done, the job unschedules it.
 */
public class StripeCustomerEmailJob implements Job {

    public static final String DATA_USER_ID = "userId";

    private static final Logger log = LoggerFactory.getLogger(StripeCustomerEmailJob.class);

    @Autowired
    private StripeCustomerEmailSync sync;

    @Override
    public void execute(JobExecutionContext context) {
        String raw = context.getMergedJobDataMap().getString(DATA_USER_ID);
        if (raw == null) {
            log.warn("StripeCustomerEmailJob fired without {} in data map; nothing to do", DATA_USER_ID);
            unschedule(context);
            return;
        }
        // The trigger has already advanced: no next fire time means this is the last attempt.
        boolean lastAttempt = context.getTrigger().getNextFireTime() == null;
        if (sync.push(UUID.fromString(raw), lastAttempt)) {
            unschedule(context);
        }
    }

    private static void unschedule(JobExecutionContext context) {
        try {
            context.getScheduler().unscheduleJob(context.getTrigger().getKey());
        } catch (SchedulerException e) {
            // The next attempt pushes the same email again; harmless.
            log.warn("could not unschedule {}: {}", context.getTrigger().getKey(), e.getMessage());
        }
    }
}
