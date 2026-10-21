package ai.godance.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ai.godance.config.AdminProperties;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.domain.User;

/**
 * Template contracts: first text line stays [email-stub]-verbatim, the
 * digest is fail-closed on both empty inputs, and no MailService method
 * ever propagates a sender failure.
 */
class MailServiceTest {

    EmailSender sender;
    AdminProperties admin;
    MailService mail;

    @BeforeEach
    void setUp() {
        sender = mock(EmailSender.class);
        admin = new AdminProperties();
        admin.setEmails(List.of("ops@godance.ai"));
        mail = new MailService(sender, admin);
    }

    private static User user(String email) {
        User u = new User();
        u.setEmail(email);
        u.setFullName("Mail Tester");
        u.setEmailVerificationToken("tok-123");
        return u;
    }

    @SuppressWarnings("unchecked")
    private String captureText(List<String> expectedTo, String expectedSubjectPart) {
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).send(to.capture(), subject.capture(), text.capture(), anyString());
        assertThat(to.getValue()).isEqualTo(expectedTo);
        assertThat(subject.getValue()).contains(expectedSubjectPart);
        return text.getValue();
    }

    @Test
    void sendVerificationEmail_givenUser_expectsStubVerbatimFirstLine() {
        mail.sendVerificationEmail(user("a@godance.test"));

        String text = captureText(List.of("a@godance.test"), "Verify");
        assertThat(text.lines().findFirst().orElseThrow())
                .isEqualTo("Verification URL for a@godance.test: /verify-email?token=tok-123");
    }

    @Test
    void sendSpendWarning_givenUserCap_expectsStubVerbatimFirstLine() {
        UUID id = UUID.randomUUID();
        mail.sendSpendWarning("a@godance.test", "user " + id, "daily", 8_500, 10_000);

        String text = captureText(List.of("a@godance.test"), "spending cap");
        assertThat(text.lines().findFirst().orElseThrow())
                .isEqualTo("Spending warning for user " + id
                        + ": daily usage at 8500 of 10000 cents (80% soft cap reached)");
    }

    @Test
    void sendSpendWarning_givenNoRecipient_expectsNoSend() {
        mail.sendSpendWarning(null, "api-key " + UUID.randomUUID(), "daily", 8_500, 10_000);

        verifyNoInteractions(sender);
    }

    @Test
    void sendAutoRechargeFailed_givenReason_expectsReasonInBody() {
        mail.sendAutoRechargeFailed(user("a@godance.test"), "card_declined: Your card was declined.");

        String text = captureText(List.of("a@godance.test"), "auto-recharge failed");
        assertThat(text).contains("card_declined");
    }

    @Test
    void sendTopUpPaymentFailed_givenAmount_expectsStubFirstLineWithDollarAmount() {
        mail.sendTopUpPaymentFailed(user("a@godance.test"), 40_05L);

        String text = captureText(List.of("a@godance.test"), "top-up payment failed");
        assertThat(text.lines().findFirst().orElseThrow())
                .isEqualTo("Top-up payment failed for a@godance.test: $40.05 not credited");
    }

    @Test
    void sendPricingProposalsDigest_givenPendingAndAllowlist_expectsOneEmailWithProposalLines() {
        ModelChangeProposal p = new ModelChangeProposal();
        p.setType(ProposalType.PRICE_CHANGE);
        p.setModelName("gpt-4o");
        p.setPayload(Map.of("field", "input_1k", "old_value", 100, "new_value", 200));
        p.setStatus(ProposalStatus.PENDING);
        p.setDetectedAt(Instant.now());

        mail.sendPricingProposalsDigest(List.of(p));

        String text = captureText(List.of("ops@godance.ai"), "1 pending proposal");
        assertThat(text).contains("gpt-4o [PRICE_CHANGE] input_1k: 100 → 200");
    }

    @Test
    void sendPricingProposalsDigest_givenEmptyAllowlist_expectsNoSend() {
        admin.setEmails(List.of());

        ModelChangeProposal p = new ModelChangeProposal();
        p.setType(ProposalType.NEW_MODEL);
        p.setModelName("new-model");
        p.setPayload(Map.of("field", "input_1k", "new_value", 300));
        p.setStatus(ProposalStatus.PENDING);
        p.setDetectedAt(Instant.now());

        mail.sendPricingProposalsDigest(List.of(p));

        verifyNoInteractions(sender);
    }

    @Test
    void sendPricingProposalsDigest_givenNothingPending_expectsNoSend() {
        mail.sendPricingProposalsDigest(List.of());

        verifyNoInteractions(sender);
    }

    @Test
    void allSends_givenSenderThrows_expectNoPropagation() {
        doThrow(new RuntimeException("boom")).when(sender)
                .send(anyList(), anyString(), anyString(), anyString());

        ModelChangeProposal p = new ModelChangeProposal();
        p.setType(ProposalType.PRICE_CHANGE);
        p.setModelName("gpt-4o");
        p.setPayload(Map.of("field", "input_1k", "old_value", 1, "new_value", 2));
        p.setStatus(ProposalStatus.PENDING);
        p.setDetectedAt(Instant.now());

        assertThatCode(() -> {
            mail.sendVerificationEmail(user("a@godance.test"));
            mail.sendSpendWarning("a@godance.test", "user x", "daily", 1, 2);
            mail.sendAutoRechargeFailed(user("a@godance.test"), "boom");
            mail.sendTopUpPaymentFailed(user("a@godance.test"), 1_00L);
            mail.sendPricingProposalsDigest(List.of(p));
        }).doesNotThrowAnyException();
    }
}
