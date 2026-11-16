package ai.godance.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.mcp.McpFixture.Account;
import ai.godance.mcp.McpFixture.Key;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;

/**
 * Each API key's requests to {@code /mcp} are counted by the existing
 * {@link ai.godance.security.RateLimiter}; past the limit the transport
 * answers 429 with {@code Retry-After}, before the message is read. The
 * limit is the key's: another key of the same account is not held back.
 *
 * <p>The limits are switched on here only, with a limit of three requests a
 * minute; every subject (each new key, each signup's address) is new, so the
 * shared Redis cannot carry a count over from another run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "godance.rate-limit.enabled=true",
        "godance.mcp.rate-limit.requests=3",
        "godance.mcp.rate-limit.window=1m"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class McpRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @LocalServerPort int port;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;

    @Test
    void post_givenMoreRequestsThanTheKeysLimit_expects429WithRetryAfterAndAnotherKeyUnaffected() {
        McpFixture fx = new McpFixture(http, users, ledger, port);
        Account account = fx.account(0, "198.51.100." + (1 + (int) (Math.random() * 250)));
        Key first = fx.key(account);
        Key second = fx.key(account);

        for (int i = 0; i < 3; i++) {
            listTools(first).expectStatus().isOk();
        }
        listTools(first)
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.error.data.status").isEqualTo(429)
                .jsonPath("$.error.data.retry_after_seconds").value(seconds ->
                        assertThat(((Number) seconds).longValue()).isBetween(1L, 60L));

        listTools(second).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec listTools(Key key) {
        return http.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, key.bearer())
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .exchange();
    }
}
