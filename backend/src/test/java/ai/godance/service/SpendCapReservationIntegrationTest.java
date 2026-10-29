package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.ApiKey;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.SpendHoldRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.SpendingLimitService.SpendingLimitExceededException;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Spec risk R-10: a spending cap holds even when requests arrive together
 * or are still running. Each pre-debit holds its estimate against the caps
 * in the same transaction that takes the money.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class SpendCapReservationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired UsageRecordRepository usage;
    @Autowired SpendHoldRepository holds;
    @Autowired CreditLedgerService ledger;

    private record Account(UUID id, String bearer) { }

    private Account signup() {
        String email = "caps-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Cap Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(id, 100_00L, CreditSource.PROMO, "seed-" + id, "seed-" + id, "test seed");
        return new Account(id, "Bearer " + resp.accessToken());
    }

    private void setUserDailyCap(UUID userId, long cents) {
        User u = users.findById(userId).orElseThrow();
        u.setDailyCreditCapCents(cents);
        users.save(u);
    }

    private UUID keyWithDailyCap(Account account, long cents) {
        CreateApiKeyResponse created = http.post().uri("/v1/keys")
                .header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "capped"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        ApiKey key = apiKeys.findById(created.id()).orElseThrow();
        key.setDailyCreditCapCents(cents);
        apiKeys.save(key);
        return key.getId();
    }

    private void recordUsage(UUID userId, String requestId, long cents) {
        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setModality(Modality.TEXT);
        r.setModelName("gpt-4o");
        r.setProvider(Provider.OPENAI);
        r.setInputTokens(0);
        r.setOutputTokens(0);
        r.setCostCents(cents);
        r.setRegion(Region.LOCAL);
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setOutcome(CallOutcome.OK);
        r.setRequestId(requestId);
        r.setRecordedAt(Instant.now());
        usage.save(r);
    }

    private long balance(UUID userId) {
        return users.findById(userId).orElseThrow().getCreditBalanceCents();
    }

    @Test
    void preDebit_concurrentRequestsNearTheCap_onlyThoseThatFitPass() throws Exception {
        Account a = signup();
        setUserDailyCap(a.id(), 100L);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String requestId = "cap-race-" + i + "-" + UUID.randomUUID();
            Callable<Boolean> attempt = () -> {
                try {
                    return ledger.preDebit(a.id(), null, 30L, requestId);
                } catch (SpendingLimitExceededException capReached) {
                    return false;
                }
            };
            results.add(pool.submit(attempt));
        }
        int passed = 0;
        for (Future<Boolean> r : results) {
            if (r.get()) passed++;
        }
        pool.shutdown();

        assertThat(passed).isEqualTo(3);                        // 3 × 30 = 90 ≤ 100; a fourth would make 120
        assertThat(balance(a.id())).isEqualTo(100_00L - 90L);   // refused requests took no money
    }

    @Test
    void preDebit_refundedRequest_releasesItsHold() {
        Account a = signup();
        setUserDailyCap(a.id(), 100L);
        String first = "req-a-" + UUID.randomUUID();

        assertThat(ledger.preDebit(a.id(), null, 60L, first)).isTrue();
        assertThatThrownBy(() -> ledger.preDebit(a.id(), null, 60L, "req-b-" + UUID.randomUUID()))
                .isInstanceOf(SpendingLimitExceededException.class);

        ledger.refundEstimate(a.id(), 60L, first, "upstream failed");

        assertThat(ledger.preDebit(a.id(), null, 60L, "req-c-" + UUID.randomUUID())).isTrue();
    }

    @Test
    void preDebit_holdStopsCountingOnceItsUsageRowExists() {
        Account a = signup();
        setUserDailyCap(a.id(), 100L);
        String first = "req-a-" + UUID.randomUUID();
        ledger.preDebit(a.id(), null, 60L, first);
        recordUsage(a.id(), first, 60L);   // the request completed: usage now counts its 60

        assertThat(ledger.preDebit(a.id(), null, 40L, "req-b-" + UUID.randomUUID())).isTrue();   // 60 + 40 = 100
        assertThatThrownBy(() -> ledger.preDebit(a.id(), null, 1L, "req-c-" + UUID.randomUUID()))
                .isInstanceOf(SpendingLimitExceededException.class);
    }

    @Test
    void preDebit_perKeyCap_countsOnlyThatKey() {
        Account a = signup();
        UUID capped = keyWithDailyCap(a, 50L);

        assertThat(ledger.preDebit(a.id(), capped, 30L, "k-a-" + UUID.randomUUID())).isTrue();
        assertThatThrownBy(() -> ledger.preDebit(a.id(), capped, 30L, "k-b-" + UUID.randomUUID()))
                .isInstanceOf(SpendingLimitExceededException.class)
                .hasMessageContaining("API-key daily spending cap");
        // The account itself has no cap; a session request still goes through.
        assertThat(ledger.preDebit(a.id(), null, 30L, "k-c-" + UUID.randomUUID())).isTrue();
    }

    @Test
    void preDebit_withoutAnyCap_writesNoHold() {
        Account a = signup();
        String requestId = "nocap-" + UUID.randomUUID();

        assertThat(ledger.preDebit(a.id(), null, 30L, requestId)).isTrue();

        assertThat(holds.existsById(requestId)).isFalse();
    }
}
