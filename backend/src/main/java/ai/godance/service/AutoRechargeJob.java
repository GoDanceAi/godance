package ai.godance.service;

import java.util.UUID;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job that runs {@link AutoRechargeService#chargeIfNeeded} for one
 * user id passed in via the trigger's data map under {@code userId}.
 *
 * <p>Spring Boot's Quartz starter wires {@code AutowireCapableBeanJobFactory},
 * so {@code @Autowired} works on plain {@link Job} instances.
 */
public class AutoRechargeJob implements Job {

    public static final String DATA_USER_ID = "userId";

    private static final Logger log = LoggerFactory.getLogger(AutoRechargeJob.class);

    @Autowired
    private AutoRechargeService autoRecharge;

    @Override
    public void execute(JobExecutionContext context) {
        String raw = context.getMergedJobDataMap().getString(DATA_USER_ID);
        if (raw == null) {
            log.warn("AutoRechargeJob fired without {} in data map; nothing to do", DATA_USER_ID);
            return;
        }
        UUID userId = UUID.fromString(raw);
        AutoRechargeService.Outcome outcome = autoRecharge.chargeIfNeeded(userId);
        log.debug("AutoRechargeJob user={} outcome={}", userId, outcome);
    }
}
