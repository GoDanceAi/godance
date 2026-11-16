package ai.godance.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;

import ai.godance.config.RateLimitProperties.Limit;
import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.RateLimitExceededException;
import ai.godance.security.RateLimiter;
import ai.godance.web.InputUploadIntake;
import reactor.core.publisher.Mono;

/**
 * MCP's Streamable HTTP transport (specification 2025-11-25), the stateless
 * subset Godance needs, on the existing WebFlux application at
 * {@code /mcp}. Each tool call is an independent request, so there are no
 * sessions and no server-to-client stream.
 *
 * <ul>
 *   <li>{@code POST /mcp} carries one JSON-RPC message. A request is answered
 *       200 with one JSON-RPC response as {@code application/json}; a
 *       notification 202 with no body; anything else 400.</li>
 *   <li>{@code GET} and {@code DELETE} answer 405: the server opens no stream
 *       and keeps no session.</li>
 *   <li>A request with an {@code Origin} header is refused (403). MCP clients
 *       send none; a web page always does, so a page cannot drive the tools
 *       even if a browser holds a key (the specification's DNS-rebinding
 *       rule).</li>
 *   <li>The body is read no further than {@code maxBodyBytes}: the largest
 *       image {@code upload_input} accepts, base64-encoded, with room for the
 *       message around it. Past it, 413 before anything is parsed.</li>
 *   <li>Each request counts against the key's limit in the existing
 *       {@link RateLimiter} before its body is read; over it, 429 with
 *       {@code Retry-After}.</li>
 * </ul>
 *
 * <p>Who may call is settled before this runs: {@code SecurityConfig}
 * admits {@code /mcp} for an API key and nothing else.
 */
public final class McpHttpTransport implements McpStatelessServerTransport {

    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);

    public static final String PATH = "/mcp";
    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    static final String RATE_LIMIT_BUCKET = "mcp-key";

    private final McpJsonMapper jsonMapper;
    private final RateLimiter limiter;
    private final Limit limit;
    private final int maxBodyBytes;
    private volatile McpStatelessServerHandler handler;
    private volatile boolean closing;

    McpHttpTransport(McpJsonMapper jsonMapper, RateLimiter limiter, Limit limit, int maxBodyBytes) {
        this.jsonMapper = jsonMapper;
        this.limiter = limiter;
        this.limit = limit;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.handler = handler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> closing = true);
    }

    /** The routes of {@code /mcp}. */
    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .POST(PATH, this::post)
                .GET(PATH, request -> methodNotAllowed())
                .DELETE(PATH, request -> methodNotAllowed())
                .build();
    }

    private Mono<ServerResponse> post(ServerRequest request) {
        if (closing) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.INTERNAL_ERROR, "The server is shutting down.");
        }
        HttpHeaders headers = request.headers().asHttpHeaders();
        if (headers.getOrigin() != null) {
            return error(HttpStatus.FORBIDDEN, ErrorCodes.INVALID_REQUEST,
                    "/mcp is for MCP clients; requests from web pages (with an Origin header) are refused.");
        }
        String version = headers.getFirst(PROTOCOL_VERSION_HEADER);
        if (version != null && !protocolVersions().contains(version.trim())) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version " + version + "; supported: " + protocolVersions());
        }
        MediaType contentType = headers.getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.INVALID_REQUEST,
                    "Content-Type must be application/json.");
        }
        if (!acceptsJson(headers.getAccept())) {
            return error(HttpStatus.NOT_ACCEPTABLE, ErrorCodes.INVALID_REQUEST,
                    "This server answers with application/json; the Accept header must allow it.");
        }
        if (headers.getContentLength() > maxBodyBytes) {
            return tooLarge();
        }
        return caller(request)
                .flatMap(caller -> limiter.check(RATE_LIMIT_BUCKET, caller.apiKeyId().toString(), limit)
                        .then(body(request))
                        .flatMap(body -> dispatch(caller, body)))
                .switchIfEmpty(Mono.defer(() -> error(HttpStatus.FORBIDDEN, ErrorCodes.INVALID_REQUEST,
                        "/mcp takes a Godance API key as its bearer token.")))
                .onErrorResume(RateLimitExceededException.class, this::rateLimited)
                .onErrorResume(DataBufferLimitException.class, e -> tooLarge());
    }

    /** One JSON-RPC message, handled by the MCP server as the caller. */
    private Mono<ServerResponse> dispatch(McpCaller caller, String body) {
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
        } catch (IOException | IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, ErrorCodes.PARSE_ERROR, "The body is not one JSON-RPC message.");
        }
        McpTransportContext context = McpTransportContext.create(Map.of(McpCaller.CONTEXT_KEY, caller));
        if (message instanceof JSONRPCRequest call) {
            return handler.handleRequest(context, call)
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                    .flatMap(response -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(write(response)))
                    .onErrorResume(e -> {
                        log.error("MCP request {} failed", call.method(), e);
                        return respond(HttpStatus.INTERNAL_SERVER_ERROR, call.id(),
                                ErrorCodes.INTERNAL_ERROR, "Internal error.", null);
                    });
        }
        if (message instanceof JSONRPCNotification notification) {
            return handler.handleNotification(context, notification)
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                    .then(ServerResponse.accepted().build());
        }
        return error(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                "This server takes requests and notifications only.");
    }

    /**
     * The API key the request was admitted with. Empty for anything else,
     * which {@code SecurityConfig} does not let through to here.
     */
    private static Mono<McpCaller> caller(ServerRequest request) {
        String ip = InputUploadIntake.clientIp(request.exchange());
        return request.principal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .filter(jwt -> jwt.hasClaim(ApiKeyAuthFilter.API_KEY_ID_CLAIM))
                .map(jwt -> new McpCaller(UUID.fromString(jwt.getSubject()), keyId(jwt), ip));
    }

    private static UUID keyId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(ApiKeyAuthFilter.API_KEY_ID_CLAIM));
    }

    /** The body as text, read no further than the limit. */
    private Mono<String> body(ServerRequest request) {
        return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), maxBodyBytes)
                .map(buffer -> {
                    try {
                        return buffer.toString(StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty("");
    }

    /** No Accept header accepts anything; otherwise one of its types must cover application/json. */
    private static boolean acceptsJson(List<MediaType> accept) {
        return accept.isEmpty() || accept.stream().anyMatch(type -> type.includes(MediaType.APPLICATION_JSON));
    }

    private Mono<ServerResponse> rateLimited(RateLimitExceededException e) {
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(write(new JSONRPCResponse(McpSchema.JSONRPC_VERSION, null, null,
                        new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "Too many requests for this API key: " + e.getReason(),
                                Map.of("status", 429, "retry_after_seconds", e.getRetryAfterSeconds())))));
    }

    private Mono<ServerResponse> tooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, ErrorCodes.INVALID_REQUEST,
                "The message is larger than this server takes (" + maxBodyBytes + " bytes).");
    }

    private static Mono<ServerResponse> methodNotAllowed() {
        return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private Mono<ServerResponse> error(HttpStatus status, int code, String message) {
        return respond(status, null, code, message, null);
    }

    private Mono<ServerResponse> respond(HttpStatus status, Object id, int code, String message, Object data) {
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(write(new JSONRPCResponse(McpSchema.JSONRPC_VERSION, id, null,
                        new JSONRPCError(code, message, data))));
    }

    private String write(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("could not write an MCP message", e);
        }
    }
}
