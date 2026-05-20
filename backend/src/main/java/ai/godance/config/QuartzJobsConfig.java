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
}
