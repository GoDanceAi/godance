package ai.godance.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import ai.godance.domain.CreditSource;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the MCP tests share: accounts with credit and keys made through the
 * REST API, a real MCP client on {@code /mcp}, and readers for what a tool
 * answers.
 */
final class McpFixture {

    record Account(UUID id, String bearer) { }

    record Key(UUID id, String plaintext) {
        String bearer() {
            return "Bearer " + plaintext;
        }
    }

    record Recipe(String id, String versionId) { }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebTestClient http;
    private final UserRepository users;
    private final CreditLedgerService ledger;
    private final int port;

    McpFixture(WebTestClient http, UserRepository users, CreditLedgerService ledger, int port) {
        this.http = http;
        this.users = users;
        this.ledger = ledger;
        this.port = port;
    }

    /** A new account with {@code cents} of credit; the signup comes from {@code clientIp}. */
    Account account(long cents, String clientIp) {
        String email = "mcp-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "MCP Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Account(userId, "Bearer " + resp.accessToken());
    }

    Account account(long cents) {
        return account(cents, "203.0.113." + (1 + (int) (Math.random() * 250)));
    }

    Key key(Account account) {
        return key(account, Map.of("name", "mcp"));
    }

    /** A key made through {@code POST /v1/keys} with this body. */
    Key key(Account account, Map<String, Object> body) {
        Map<?, ?> created = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Key(UUID.fromString((String) created.get("id")), (String) created.get("plaintext"));
    }

    /** A recipe made through {@code POST /v1/recipes}. */
    Recipe recipe(Account account, String modality, String template, List<Map<String, String>> variables,
                  String model, Map<String, Object> params, List<Map<String, String>> slots) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "An MCP recipe");
        body.put("modality", modality);
        body.put("template", template);
        body.put("variables", variables);
        body.put("model", model);
        body.put("params", params);
        if (!slots.isEmpty()) body.put("inputs", slots);
        Map<?, ?> created = http.post().uri("/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Recipe((String) created.get("id"), (String) ((Map<?, ?>) created.get("latest_version")).get("id"));
    }

    /** An MCP client for {@code /mcp} on this server, sending the key as its bearer; initialised. */
    McpSyncClient client(Key key) {
        McpSyncClient client = uninitialised(key.plaintext());
        client.initialize();
        return client;
    }

    McpSyncClient uninitialised(String bearerToken) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    /** A successful result's structured content. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> ok(CallToolResult result) {
        assertThat(result.isError()).as("tool error: %s", text(result)).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        return (Map<String, Object>) result.structuredContent();
    }

    /** A tool error's REST problem, from its text. */
    static Map<String, Object> problem(CallToolResult result) {
        assertThat(result.isError()).as("expected a tool error, got: %s", text(result)).isTrue();
        assertThat(result.structuredContent()).as("an error carries no structured content").isNull();
        String text = text(result);
        return JSON.readValue(text.substring(text.indexOf("\n\n") + 2), new TypeReference<Map<String, Object>>() { });
    }

    static String text(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .findFirst()
                .orElse("");
    }

    /** Calls get_job until the job reads {@code status}; fails after 20 seconds. */
    static Map<String, Object> job(McpSyncClient client, Object jobId, String status) throws InterruptedException {
        Map<String, Object> job = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            job = ok(call(client, "get_job", Map.of("job_id", String.valueOf(jobId))));
            if (status.equals(job.get("status"))) return job;
            Thread.sleep(50);
        }
        assertThat(job).as("job %s never reached %s; last read: %s", jobId, status, job).isNull();
        return job;
    }
}
