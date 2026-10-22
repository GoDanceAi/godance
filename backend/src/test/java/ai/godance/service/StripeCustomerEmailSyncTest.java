package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;

/**
 * {@link StripeCustomerEmailSync} pushes an account's email to its Stripe
 * customer only after the change commits, clears it for a placeholder,
 * and asks for another attempt only for a failure that may pass.
 */
class StripeCustomerEmailSyncTest {

    UserRepository users;
    StripeService stripe;
    AuditLogService audit;
    Scheduler scheduler;
    StripeCustomerEmailSync sync;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        stripe = mock(StripeService.class);
        audit = mock(AuditLogService.class);
        scheduler = mock(Scheduler.class);
        sync = new StripeCustomerEmailSync(users, stripe, audit, scheduler);
    }

    private User user(String email, String customerId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setStripeCustomerId(customerId);
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    // ---------- push --------------------------------------------------

    @Test
    void push_givenNoStripeCustomer_isDoneWithoutCallingStripe() {
        User u = user("a@godance.test", null);

        assertThat(sync.push(u.getId(), false)).isTrue();

        verifyNoInteractions(stripe, audit);
    }

    @Test
    void push_givenRealAddress_setsItOnTheCustomerAndAuditsSuccess() throws Exception {
        User u = user("new@godance.test", "cus_1");

        assertThat(sync.push(u.getId(), false)).isTrue();

        verify(stripe).updateCustomerEmail("cus_1", "new@godance.test");
        verify(audit).record(eq(AuditLogService.EVT_BILLING_CUSTOMER_EMAIL_SYNC),
                eq(AuditOutcome.SUCCESS), eq(u.getId()), any(), anyMap());
    }

    @Test
    void push_givenPlaceholderAddress_clearsTheCustomersEmail() throws Exception {
        User u = user("released-1" + User.SYNTHETIC_EMAIL_SUFFIX, "cus_1");

        assertThat(sync.push(u.getId(), false)).isTrue();

        verify(stripe).updateCustomerEmail("cus_1", null);
    }

    @Test
    void push_givenStripeUnreachable_asksForAnotherAttemptWithoutAuditing() throws Exception {
        User u = user("new@godance.test", "cus_1");
        doThrow(new ApiConnectionException("connect timed out"))
                .when(stripe).updateCustomerEmail(any(), any());

        assertThat(sync.push(u.getId(), false)).isFalse();

        verifyNoInteractions(audit);
    }

    @Test
    void push_givenStripeServerErrorOnLastAttempt_givesUpAndAuditsFailure() throws Exception {
        User u = user("new@godance.test", "cus_1");
        doThrow(new ApiException("internal error", "req_1", null, 500, null))
                .when(stripe).updateCustomerEmail(any(), any());

        assertThat(sync.push(u.getId(), true)).isTrue();

        verify(audit).record(eq(AuditLogService.EVT_BILLING_CUSTOMER_EMAIL_SYNC),
                eq(AuditOutcome.FAILURE), eq(u.getId()), any(), anyMap());
    }

    @Test
    void push_givenCustomerDeletedInStripe_givesUpAtOnceAndAuditsFailure() throws Exception {
        User u = user("new@godance.test", "cus_gone");
        doThrow(new InvalidRequestException("No such customer: 'cus_gone'", "id", "req_1",
                "resource_missing", 404, null))
                .when(stripe).updateCustomerEmail(any(), any());

        assertThat(sync.push(u.getId(), false)).isTrue();

        verify(audit).record(eq(AuditLogService.EVT_BILLING_CUSTOMER_EMAIL_SYNC),
                eq(AuditOutcome.FAILURE), eq(u.getId()), any(), anyMap());
    }

    // ---------- syncAfterCommit ----------------------------------------

    @Test
    void syncAfterCommit_givenNoStripeCustomer_schedulesNothing() {
        User u = user("new@godance.test", null);

        sync.syncAfterCommit(u);

        verifyNoInteractions(scheduler);
    }

    @Test
    void syncAfterCommit_insideTransaction_schedulesOnlyOnceItCommits() throws Exception {
        User u = user("new@godance.test", "cus_1");
        TransactionSynchronizationManager.initSynchronization();
        try {
            sync.syncAfterCommit(u);
            verifyNoInteractions(scheduler);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(scheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void syncAfterCommit_givenTwoChanges_schedulesARetryingJobForEach() throws Exception {
        User u = user("new@godance.test", "cus_1");

        sync.syncAfterCommit(u);
        sync.syncAfterCommit(u);

        ArgumentCaptor<JobDetail> jobs = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler, times(2)).scheduleJob(jobs.capture(), triggers.capture());
        List<JobDetail> scheduled = jobs.getAllValues();
        // A pending job is never replaced: it may already be running with the older email.
        assertThat(scheduled.get(0).getKey()).isNotEqualTo(scheduled.get(1).getKey());
        assertThat(scheduled.get(0).getJobClass()).isEqualTo(StripeCustomerEmailJob.class);
        assertThat(scheduled.get(0).getJobDataMap().getString(StripeCustomerEmailJob.DATA_USER_ID))
                .isEqualTo(u.getId().toString());
        SimpleTrigger trigger = (SimpleTrigger) triggers.getAllValues().get(0);
        assertThat(trigger.getRepeatCount()).isEqualTo(StripeCustomerEmailSync.MAX_ATTEMPTS - 1);
        assertThat(trigger.getRepeatInterval())
                .isEqualTo(StripeCustomerEmailSync.RETRY_INTERVAL_MINUTES * 60_000L);
    }

    @Test
    void syncAfterCommit_givenSchedulerFailure_doesNotFailTheCaller() throws Exception {
        User u = user("new@godance.test", "cus_1");
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenThrow(new SchedulerException("job store unavailable"));

        assertThatCode(() -> sync.syncAfterCommit(u)).doesNotThrowAnyException();
        verify(stripe, never()).updateCustomerEmail(any(), any());
    }
}
