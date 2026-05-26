package ai.godance.service.pricing;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.PersistJobDataAfterExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import ai.godance.domain.Region;

/**
 * Daily refresh of the model catalog from upstream pricing feeds.
 * Mirrors the {@link ai.godance.service.AutoRechargeCronJob} pattern:
 * {@code @DisallowConcurrentExecution} prevents a slow run from
 * overlapping its next firing.
 *
 * <p><b>Region gate.</b> The job runs only when {@code godance.region ==
 * DIGITALOCEAN}. Same single-region gotcha as auto-recharge: an
 * Alicloud failover must not double-fire the staging-lane writes (and
 * is also blocked from reaching most Western pricing APIs). Local dev
 * is excluded too — the daily catalog drift conversation belongs to
 * one production region, not every developer laptop.
 *
 * <p>Cron expression and feed URLs come from
 * {@link ai.godance.config.PricingProperties} so the operator can
 * retarget feeds or stub a never-fires expression in tests without a
 * rebuild. Per-region wiring is intentionally checked at run time
 * (inside {@link #execute(JobExecutionContext)}) rather than via a
 * {@code @Profile}, so test code can supply {@code godance.region=local}
 * and never trip the job by accident.
 */
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
public class PricingRefreshCronJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(PricingRefreshCronJob.class);

    @Autowired private PricingRefreshService refresh;
    @Autowired @Value("${godance.region:local}") private String regionName;

    @Override
    public void execute(JobExecutionContext context) {
        Region region = parseRegion(regionName);
        if (region != Region.DIGITALOCEAN) {
            log.debug("PricingRefreshCronJob: skipped (region={})", region);
            return;
        }
        PricingRefreshService.Digest d = refresh.runOnce();
        log.info("PricingRefreshCronJob: price_changes={} new_versions={} new_models={} anomalies={} within_noise={} no_change={} errors={}",
                d.priceChanges, d.newVersions, d.newModels, d.anomalies, d.withinNoise, d.noChange, d.errors);
    }

    private static Region parseRegion(String name) {
        if (name == null || name.isBlank()) return Region.LOCAL;
        try {
            return Region.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Region.LOCAL;
        }
    }
}
