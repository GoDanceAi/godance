package ai.godance.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link StripeCustomerEmailJob} stops its repeating trigger once a push
 * is done, keeps it for a retry, and tells the push when no attempt is left.
 */
class StripeCustomerEmailJobTest {

    private final UUID userId = UUID.randomUUID();
    private final TriggerKey triggerKey = new TriggerKey("stripe-email-x", StripeCustomerEmailSync.GROUP);

    StripeCustomerEmailSync sync;
    Scheduler scheduler;
    Trigger trigger;
    JobExecutionContext context;
    StripeCustomerEmailJob job;

    @BeforeEach
    void setUp() {
        sync = mock(StripeCustomerEmailSync.class);
        scheduler = mock(Scheduler.class);
        trigger = mock(Trigger.class);
        context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put(StripeCustomerEmailJob.DATA_USER_ID, userId.toString());
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getScheduler()).thenReturn(scheduler);
        when(context.getTrigger()).thenReturn(trigger);
        when(trigger.getKey()).thenReturn(triggerKey);
        job = new StripeCustomerEmailJob();
        ReflectionTestUtils.setField(job, "sync", sync);
    }

    @Test
    void execute_givenPushDone_unschedulesItsTrigger() throws Exception {
        when(trigger.getNextFireTime()).thenReturn(new Date());
        when(sync.push(userId, false)).thenReturn(true);

        job.execute(context);

        verify(scheduler).unscheduleJob(triggerKey);
    }

    @Test
    void execute_givenPushToRetry_keepsItsTrigger() throws Exception {
        when(trigger.getNextFireTime()).thenReturn(new Date());
        when(sync.push(userId, false)).thenReturn(false);

        job.execute(context);

        verify(scheduler, never()).unscheduleJob(any());
    }

    @Test
    void execute_givenNoFireLeft_tellsPushItIsTheLastAttempt() {
        when(trigger.getNextFireTime()).thenReturn(null);

        job.execute(context);

        verify(sync).push(eq(userId), eq(true));
        verify(sync, never()).push(any(), eq(false));
    }

    @Test
    void execute_givenNoUserId_unschedulesWithoutPushing() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

        job.execute(context);

        verify(sync, never()).push(any(), anyBoolean());
        verify(scheduler).unscheduleJob(triggerKey);
    }
}
