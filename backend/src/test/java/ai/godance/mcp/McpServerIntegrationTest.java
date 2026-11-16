package ai.godance.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Round 13 brief 01: Godance's API as MCP tools at {@code /mcp}, over
 * Streamable HTTP, behind an API key. These tests talk to the server the way
 * an agent's MCP client does: the official MCP Java SDK's client, over HTTP,
 * on a real port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class McpServerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    /** The tools of the round 13 contract ({@code 00-coordination.md}); client configurations name them. */
    static final Set<String> CONTRACT_TOOLS = Set.of(
            "search_recipes", "get_recipe", "list_models", "estimate_cost",
            "generate_image", "generate_video", "run_recipe", "start_batch",
            "get_job", "get_batch", "upload_input");

    @LocalServerPort int port;
    @Autowired WebTestClient http;

    @Test
    void listTools_givenAnApiKey_expectsExactlyTheContractTools() {
        String key = apiKeyOfNewAccount();

        try (McpSyncClient client = client(key)) {
            InitializeResult init = client.initialize();
            assertThat(init.serverInfo().name()).isEqualTo("godance");
            assertThat(init.capabilities().tools()).isNotNull();

            assertThat(client.listTools().tools()).extracting(Tool::name)
                    .containsExactlyInAnyOrderElementsOf(CONTRACT_TOOLS);
        }
    }

    // ===== Helpers ====================================================

    /** An MCP client for {@code /mcp} on this server, sending the key as its bearer. */
    McpSyncClient client(String apiKey) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    /** A new account's session bearer. */
    String signup() {
        String email = "mcp-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "MCP Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    /** The plaintext of a key made through {@code POST /v1/keys} by a new account. */
    @SuppressWarnings("unchecked")
    String apiKeyOfNewAccount() {
        Map<String, Object> created = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, signup())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "mcp"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) created.get("plaintext");
    }
}
