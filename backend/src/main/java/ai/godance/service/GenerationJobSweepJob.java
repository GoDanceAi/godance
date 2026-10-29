package ai.godance.service;

import java.util.List;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ai.godance.domain.GenerationJob;

/**
 * Fails and refunds image and video jobs whose worker can no longer be
 * running (spec risks R-03, R-05). Workers run in memory, so a deploy or a
 * crash ends them; without this the row would stay QUEUED or RUNNING with
 * its pre-debit kept and no result. Bound to a cron trigger (every two
 * minutes) in {@link ai.godance.config.QuartzJobsConfig}.
 *
 * <p>A worker that finishes after its job was swept finds the job ended
 * and settles nothing (see {@link GenerationJobSettlement}).
 */
@DisallowConcurrentExecution
public class GenerationJobSweepJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobSweepJob.class);

    @Autowired private GenerationJobSettlement settlement;

    @Override
    public void execute(JobExecutionContext context) {
        sweep(settlement);
    }

    /** One pass; returns how many jobs it failed and refunded. */
    static int sweep(GenerationJobSettlement settlement) {
        List<GenerationJob> abandoned = settlement.findAbandoned();
        int swept = 0;
        for (GenerationJob job : abandoned) {
            if (settlement.fail(job, "worker lost: the job outlived its lease",
                    "job abandoned by a restart or a worker failure")) {
                swept++;
            }
        }
        if (swept > 0) {
            log.warn("GenerationJobSweepJob: failed and refunded {} abandoned job(s)", swept);
        }
        return swept;
    }
}
