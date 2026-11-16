package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.ImageSyncService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

/**
 * A client that goes away while its image request waits (round 10 brief
 * 04): the wait stops, and the job carries on and is settled as usual.
 *
 * <p>On a real port, because the disconnect has to be a real one: the test
 * writes the request on a socket and closes it mid-wait.
 *
 * <p>A request starts its wait a moment after its job: once the job is
 * accepted, it reads whether its key waits ({@link ImageSyncService#waits}),
 * and only then subscribes to the wait ({@link ImageSyncService#await}). The
 * job can be RUNNING before that. So the tests watch those two calls through
 * a spy and leave at a known point: once the wait has started, or while the
 * key is still being read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "godance.images.sync-poll-interval=50ms"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class ImageSyncDisconnectIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base",  () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint",    MINIO::getS3URL);
        r.add("godance.object-storage.access-key",  MINIO::getUserName);
        r.add("godance.object-storage.secret-key",  MINIO::getPassword);
        r.add("godance.object-storage.bucket",      () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean ImageSyncService sync;

    @Test
    void generate_givenTheClientDisconnectsMidWait_expectsTheWaitStoppedAndTheJobFinishingNormally() throws Exception {
        Caller caller = signUpWithASyncKey("gone");
        providerTakesThreeSeconds();
        // Counts down once the request has subscribed to its wait: from then on, leaving cancels the wait.
        CountDownLatch waiting = new CountDownLatch(1);
        doAnswer(call -> ((Mono<?>) call.callRealMethod()).doOnSubscribe(s -> waiting.countDown()))
                .when(sync).await(any());
        double cancelledBefore = waits("cancelled");
        double doneBefore = waits("done");

        try (Socket socket = new Socket("127.0.0.1", port)) {
            send(socket, caller.key(), "A render nobody waits for");
            // The job is accepted and running, and the request is waiting on it. Running
            // alone is not enough: the job can start before the request's wait does.
            await(5_000, () -> jobsOf(caller.userId()).stream().anyMatch(j -> j.getStatus() == JobStatus.RUNNING));
            assertThat(waiting.await(5, TimeUnit.SECONDS)).as("the request is waiting on its job").isTrue();
        }   // the client is gone

        // The wait stops well before the job ends, and is counted as neither done nor failed.
        await(2_000, () -> waits("cancelled") == cancelledBefore + 1);
        GenerationJob job = jobsOf(caller.userId()).get(0);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

        // The job carries on: done, stored and charged once, as if the client had stayed.
        await(10_000, () -> jobsOf(caller.userId()).get(0).getStatus() == JobStatus.DONE);
        assertThat(jobsOf(caller.userId())).hasSize(1);
        assertThat(jobsOf(caller.userId()).get(0).getActualCostCents()).isEqualTo(6L);
        assertThat(ledger.authoritativeBalance(caller.userId())).isEqualTo(100_00L - 6L);
        assertThat(waits("done")).isEqualTo(doneBefore);
        assertThat(waits("cancelled")).isEqualTo(cancelledBefore + 1);
    }

    @Test
    void generate_givenTheClientDisconnectsBeforeItsWaitStarts_expectsNoWaitCountedAndTheJobFinishingNormally()
            throws Exception {
        Caller caller = signUpWithASyncKey("early");
        providerTakesThreeSeconds();
        // The request has its job and is reading whether its key waits. The read does not
        // come back before the client leaves, so the wait never starts.
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch dropped = new CountDownLatch(1);
        doAnswer(call -> Mono.never()
                .doOnSubscribe(s -> reading.countDown())
                .doOnCancel(dropped::countDown))
                .when(sync).waits(any());
        double countedBefore = waitsCounted();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            send(socket, caller.key(), "A render nobody waited for");
            await(5_000, () -> jobsOf(caller.userId()).stream().anyMatch(j -> j.getStatus() == JobStatus.RUNNING));
            assertThat(reading.await(5, TimeUnit.SECONDS)).as("the request is reading its key").isTrue();
        }   // the client is gone
        assertThat(dropped.await(2, TimeUnit.SECONDS)).as("the request is dropped").isTrue();

        // The job carries on: done, stored and charged once, as if the client had stayed.
        await(10_000, () -> jobsOf(caller.userId()).get(0).getStatus() == JobStatus.DONE);
        assertThat(jobsOf(caller.userId())).hasSize(1);
        assertThat(jobsOf(caller.userId()).get(0).getActualCostCents()).isEqualTo(6L);
        assertThat(ledger.authoritativeBalance(caller.userId())).isEqualTo(100_00L - 6L);
        // No wait began, so none is counted, under any outcome.
        verify(sync, never()).await(any());
        assertThat(waitsCounted()).isEqualTo(countedBefore);
    }

    /** An account with credit, and an API key whose image requests wait for their jobs. */
    private record Caller(UUID userId, String key) { }

    private Caller signUpWithASyncKey(String name) {
        String email = "sync-" + name + "-" + UUID.randomUUID() + "@godance.test";
        AuthResponse session = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Gone"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "image_response", "sync"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Caller(userId, (String) key.get("plaintext"));
    }

    /** The provider takes three seconds: long enough to leave while the request waits. */
    private static void providerTakesThreeSeconds() {
        String b64 = Base64.getEncoder().encodeToString(ImageSyncResponseIntegrationTest.TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(3_000)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    /** Writes the image request on the socket. The answer is never read. */
    private void send(Socket socket, String key, String prompt) throws IOException {
        String body = "{\"model\":\"gpt-image-2\",\"prompt\":\"" + prompt + "\"}";
        OutputStream out = socket.getOutputStream();
        out.write(("POST /v1/images/generations HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Authorization: Bearer " + key + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n" + body).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private List<GenerationJob> jobsOf(UUID userId) {
        return jobs.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private double waits(String outcome) {
        return meters.counter(ImageSyncService.WAITS_METER, "outcome", outcome).count();
    }

    /** Waits counted under any outcome. */
    private double waitsCounted() {
        return meters.find(ImageSyncService.WAITS_METER).counters().stream().mapToDouble(Counter::count).sum();
    }

    private static void await(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("condition met within %d ms", timeoutMs).isLessThan(deadline);
            Thread.sleep(25);
        }
    }
}
