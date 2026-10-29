package ai.godance.config;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ai.godance.service.AutoRechargeCronJob;
import ai.godance.service.GenerationJobSweepJob;
import ai.godance.service.pricing.PricingRefreshCronJob;

/**
 * Quartz {@link JobDetail} + {@link Trigger} beans for static, system-wide
 * jobs. Spring Boot's Quartz auto-configuration picks them up and registers
 * them with the scheduler on startup; misfires (job exists but cron changed)
 * are reconciled automatically.
 *
 * <p>Per-user one-shot jobs (debit-driven auto-recharge) are scheduled
 * imperatively from {@code AutoRechargeTrigger}, not here.
 */
@Configuration
public class QuartzJobsConfig {

    @Bean
    JobDetail autoRechargeCronJobDetail() {
        return JobBuilder.newJob(AutoRechargeCronJob.class)
                .withIdentity("autoRechargeCronJob")
                .storeDurably()       // required for jobs without an immediate trigger binding
                .build();
    }

    /**
     * Cron expression is configurable so tests can stub a never-fires
     * expression; production defaults to {@code 0 0 3 * * ?} (3 a.m. UTC
     * daily — quiet hour for the US/EU traffic mix).
     */
    @Bean
    Trigger autoRechargeCronTrigger(
            JobDetail autoRechargeCronJobDetail,
            @Value("${godance.scheduling.auto-recharge-cron:0 0 3 * * ?}") String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(autoRechargeCronJobDetail)
                .withIdentity("autoRechargeCronTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .inTimeZone(java.util.TimeZone.getTimeZone("UTC"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

    @Bean
    JobDetail pricingRefreshCronJobDetail() {
        return JobBuilder.newJob(PricingRefreshCronJob.class)
                .withIdentity("pricingRefreshCronJob")
                .storeDurably()
                .build();
    }

    /**
     * Default 04:00 UTC daily — one hour after auto-recharge so the two
     * cron windows don't collide. Region gating happens inside
     * {@link PricingRefreshCronJob#execute}: outside DIGITALOCEAN the
     * trigger still fires but the job is a no-op.
     */
    @Bean
    Trigger pricingRefreshCronTrigger(
            JobDetail pricingRefreshCronJobDetail,
            @Value("${godance.pricing.refresh-cron:0 0 4 * * ?}") String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(pricingRefreshCronJobDetail)
                .withIdentity("pricingRefreshCronTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .inTimeZone(java.util.TimeZone.getTimeZone("UTC"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

    @Bean
    JobDetail generationJobSweepJobDetail() {
        return JobBuilder.newJob(GenerationJobSweepJob.class)
                .withIdentity("generationJobSweepJob")
                .storeDurably()
                .build();
    }

    /**
     * Every two minutes by default: an abandoned media job is refunded within
     * a couple of minutes of its lease running out (spec risks R-03, R-05).
     */
    @Bean
    Trigger generationJobSweepTrigger(
            JobDetail generationJobSweepJobDetail,
            @Value("${godance.scheduling.job-sweep-cron:0 */2 * * * ?}") String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(generationJobSweepJobDetail)
                .withIdentity("generationJobSweepTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .inTimeZone(java.util.TimeZone.getTimeZone("UTC"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
