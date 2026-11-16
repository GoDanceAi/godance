package ai.godance.mcp;

import java.util.UUID;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Who a tool call runs as: the API key's user, the key, and the address the
 * request came from (for the upload limits and the audit log, as on REST).
 * The transport puts it in each call's {@link McpTransportContext}; every
 * tool reads it from there and passes it to the services, so a tool can only
 * ever act as the key that called it.
 */
record McpCaller(UUID userId, UUID apiKeyId, String clientIp) {

    static final String CONTEXT_KEY = "godance.mcp.caller";

    static McpCaller from(McpTransportContext context) {
        if (context != null && context.get(CONTEXT_KEY) instanceof McpCaller caller) {
            return caller;
        }
        // Cannot happen through /mcp: the transport refuses a request it cannot attribute to a key.
        throw new IllegalStateException("tool call without an authenticated API key");
    }
}
