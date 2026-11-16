package ai.godance.mcp;

import static ai.godance.mcp.McpFixture.call;
import static ai.godance.mcp.McpFixture.problem;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.modelcontextprotocol.client.McpSyncClient;

import ai.godance.mcp.McpFixture.Account;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.InputFixtures;

/**
 * While inputs are switched off, which they are by default, every tool that
 * takes an image answers {@code inputs_disabled}, as the REST routes do, and
 * nothing is stored or charged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class McpInputsOffIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @LocalServerPort int port;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired JdbcTemplate jdbc;

    @Test
    void imageTools_givenInputsSwitchedOff_expectInputsDisabledAndNothingStoredOrCharged() {
        McpFixture fx = new McpFixture(http, users, ledger, port);
        Account account = fx.account(10_00L);
        Map<String, Object> someFile = Map.of("file_id", UUID.randomUUID().toString());

        try (McpSyncClient client = fx.client(fx.key(account))) {
            assertThat(problem(call(client, "upload_input",
                    Map.of("data", Base64.getEncoder().encodeToString(InputFixtures.png(8, 8))))))
                    .containsEntry("status", 403).containsEntry("code", "inputs_disabled");
            assertThat(problem(call(client, "generate_image",
                    Map.of("model", "gpt-image-2", "prompt", "Edit", "images", List.of(someFile)))))
                    .containsEntry("code", "inputs_disabled");
            assertThat(problem(call(client, "generate_video",
                    Map.of("model", "sora-2", "prompt", "From a frame", "first_frame", someFile))))
                    .containsEntry("code", "inputs_disabled");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM input_files WHERE user_id = ?", Long.class,
                account.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_jobs WHERE user_id = ?", Long.class,
                account.id())).isZero();
        assertThat(users.findById(account.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10_00L);
    }
}
