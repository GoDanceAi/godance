package ai.godance.mcp;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import ai.godance.security.RateLimiter;
import ai.godance.service.InputService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server at {@code /mcp} (round 13 brief 01): Godance's API as tools
 * for agents running in an MCP client (Claude Code, Cursor, the MCP
 * Inspector, …).
 *
 * <p>Godance runs no agent and makes no tool call of its own: it answers the
 * tool calls a client sends, each one as the API key the client sent, through
 * the services the REST API uses. The server is the official MCP Java SDK's
 * stateless server ({@code io.modelcontextprotocol.sdk:mcp}) behind
 * {@link McpHttpTransport}. Tools only: no resources, prompts, sampling or
 * sessions.
 *
 * <p>{@code godance.mcp.enabled=false} leaves {@code /mcp} unrouted.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
class McpServerConfig {

    static final String SERVER_NAME = "godance";

    /** What a client shows the model about the server as a whole. */
    static final String INSTRUCTIONS = """
            Godance makes images and videos with many models through one API. These tools act as the \
            Godance API key in this client's configuration: the account's credit, the key's spending caps \
            and its image setting apply exactly as on the REST API, and nothing here can manage keys, \
            billing or the account.
            - Find a prompt: search_recipes, then get_recipe.
            - Price before spending: estimate_cost. Every tool that spends (generate_image, \
            generate_video, run_recipe, start_batch) takes max_cost_cents and refuses, charging nothing, \
            when the price is above it. Always pass it.
            - Generation runs as a job: follow it with get_job until DONE or FAILED (a failed job is \
            refunded). Batches: get_batch.
            - Own images: upload_input, then name the file as {"file_id": "…"}.
            - Outputs are signed links that expire; get_job again for fresh ones.
            Errors carry the REST problem's status, code and detail, for example insufficient_credit, \
            max_cost_exceeded, or inputs_terms_required (the user must accept the input terms in the \
            Godance app).""";

    /** The protocol's own JSON: a plain Jackson 3 mapper, apart from the HTTP API's settings. */
    @Bean
    McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    @Bean
    ToolSupport mcpToolSupport(McpJsonMapper mcpJsonMapper, ObjectMapper json) {
        return new ToolSupport(mcpJsonMapper, new ToolResults(json));
    }

    /**
     * The transport, reading a message no larger than the largest image an
     * upload takes, base64-encoded, and 64 KiB for the message around it.
     */
    @Bean
    @ConditionalOnProperty(prefix = "godance.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
    McpHttpTransport mcpHttpTransport(McpJsonMapper mcpJsonMapper, RateLimiter limiter, McpProperties props,
                                      InputService inputs) {
        long base64 = (inputs.maxBytes() + 2) / 3 * 4;
        int maxBody = (int) Math.min(Integer.MAX_VALUE, base64 + 64 * 1024);
        return new McpHttpTransport(mcpJsonMapper, limiter, props.rateLimit(), maxBody);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "godance.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
    McpStatelessAsyncServer mcpServer(McpHttpTransport transport, McpJsonMapper mcpJsonMapper,
                                      List<McpToolProvider> providers, ObjectProvider<BuildProperties> build) {
        List<AsyncToolSpecification> tools = providers.stream().flatMap(p -> p.tools().stream()).toList();
        BuildProperties info = build.getIfAvailable();
        return McpServer.async(transport)
                .serverInfo(SERVER_NAME, info != null && info.getVersion() != null ? info.getVersion() : "dev")
                .instructions(INSTRUCTIONS)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .jsonMapper(mcpJsonMapper)
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(JsonMapper.builder().build()))
                .validateToolInputs(true)
                .strictToolNameValidation(true)
                .tools(tools)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "godance.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
    RouterFunction<ServerResponse> mcpRoutes(McpHttpTransport transport, McpStatelessAsyncServer server) {
        // The server is built before the routes exist, so no request reaches a transport without its handler.
        return transport.routes();
    }
}
