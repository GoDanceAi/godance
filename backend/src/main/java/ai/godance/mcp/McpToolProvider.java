package ai.godance.mcp;

import java.util.List;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;

/** A group of tools the MCP server offers. */
interface McpToolProvider {

    List<AsyncToolSpecification> tools();
}
