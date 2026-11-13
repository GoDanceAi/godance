package ai.godance.service;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pays referral rewards the top-up path did not: one that failed, or whose
 * top-up committed in a process that stopped before the reward ran (see
 * {@link ReferralRewardService#sweep}). Does nothing while the feature is
 * off. Bound to a cron trigger (hourly) in {@link ai.godance.config.QuartzJobsConfig}.
 */
@DisallowConcurrentExecution
public class ReferralSweepJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReferralSweepJob.class);

    @Autowired private ReferralRewardService rewards;

    @Override
    public void execute(JobExecutionContext context) {
        run(rewards);
    }

    /** One pass; returns how many referrals it rewarded. */
    static int run(ReferralRewardService rewards) {
        int rewarded = rewards.sweep();
        if (rewarded > 0) {
            log.warn("ReferralSweepJob: rewarded {} referral(s) the top-up path had not", rewarded);
        }
        return rewarded;
    }
}
