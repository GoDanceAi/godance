package ai.godance.service;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Runs one pass of {@link RecipeBatchRunner} every few seconds (round 12
 * brief 01). Bound to a cron trigger in
 * {@link ai.godance.config.QuartzJobsConfig}.
 *
 * <p>Single-instance, as the other scheduled jobs are: one scheduler runs
 * in the active region, and a pass that is still running when the next is
 * due is not joined by a second one. The runner does not depend on that
 * for correctness: every change it makes is a conditional update, and the
 * database allows one job per batch row.
 */
@DisallowConcurrentExecution
public class RecipeBatchTickJob implements Job {

    @Autowired private RecipeBatchRunner runner;

    @Override
    public void execute(JobExecutionContext context) {
        runner.tick();
    }
}
