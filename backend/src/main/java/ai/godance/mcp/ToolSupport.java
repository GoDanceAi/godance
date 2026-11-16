package ai.godance.mcp;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * How every tool is built and run. A tool is its definition (name,
 * description, input and output schema, hints) and a handler that takes the
 * caller and the arguments. Whatever the handler throws becomes a tool error
 * carrying the REST problem ({@link ToolResults}); nothing escapes as a
 * protocol error.
 */
final class ToolSupport {

    /** What a tool does with a call. */
    @FunctionalInterface
    interface Handler {
        Mono<CallToolResult> call(McpCaller caller, ToolArgs args);
    }

    private final McpJsonMapper mcpJson;
    private final ToolResults results;

    ToolSupport(McpJsonMapper mcpJson, ToolResults results) {
        this.mcpJson = mcpJson;
        this.results = results;
    }

    ToolResults results() {
        return results;
    }

    /** A tool that reads only: it charges nothing and changes nothing. */
    Tool readOnly(String name, String title, String description, String inputSchema, String outputSchema) {
        return define(name, title, description, inputSchema, outputSchema,
                new ToolAnnotations(title, true, false, true, false, null));
    }

    /**
     * A tool that acts: it may charge credit or store a file. Not destructive
     * (it deletes nothing) and not idempotent (calling it twice does it twice).
     */
    Tool acting(String name, String title, String description, String inputSchema, String outputSchema) {
        return define(name, title, description, inputSchema, outputSchema,
                new ToolAnnotations(title, false, false, false, true, null));
    }

    /** The tool with its handler; a handler's error is answered as a tool error, never thrown. */
    AsyncToolSpecification spec(Tool tool, Handler handler) {
        BiFunction<io.modelcontextprotocol.common.McpTransportContext, io.modelcontextprotocol.spec.McpSchema.CallToolRequest,
                Mono<CallToolResult>> call = (context, request) -> Mono
                .defer(() -> handler.call(McpCaller.from(context), new ToolArgs(request.arguments())))
                .onErrorResume(error -> Mono.just(results.error(tool.name(), error)));
        return new AsyncToolSpecification(tool, call);
    }

    /** Blocking service work, on the worker pool as the REST controllers run it. */
    static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    private Tool define(String name, String title, String description, String inputSchema, String outputSchema,
                        ToolAnnotations annotations) {
        return Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(mcpJson, inputSchema)
                .outputSchema(mcpJson, outputSchema)
                .annotations(annotations)
                .build();
    }

    /** An input image, as every route that takes one names it. */
    static final String INPUT_REF = """
            {"type": "object",
             "description": "An input image: {\\"file_id\\": \\"…\\"} for a file from upload_input, or {\\"job_output\\": {\\"job_id\\": \\"…\\", \\"index\\": 0}} for an output of one of the caller's finished jobs.",
             "properties": {
               "file_id": {"type": "string", "description": "An input file's id, from upload_input."},
               "job_output": {"type": "object",
                 "properties": {
                   "job_id": {"type": "string", "description": "One of the caller's DONE image jobs."},
                   "index": {"type": "integer", "minimum": 0, "description": "Which output of that job; 0 when left out."}
                 },
                 "required": ["job_id"]}
             }}""";

    /** {@code max_cost_cents}, as every tool that spends takes it. */
    static final String MAX_COST = """
            {"type": "integer", "minimum": 0,
             "description": "The most this may cost, in US cents. When the price is above it the tool refuses (code max_cost_exceeded, with the price in estimated_cost_cents) and nothing is charged. Get the price from estimate_cost first."}""";

    /** What a tool that starts a job answers. */
    static final String JOB_OUTPUT = """
            {"type": "object",
             "properties": {
               "job_id": {"type": "string", "description": "Follow it with get_job."},
               "status": {"type": "string", "description": "QUEUED or RUNNING: call get_job. DONE: the images are in images."},
               "poll_url": {"type": "string", "description": "The REST route for the same job: GET it with the API key."},
               "estimated_cost_cents": {"type": "integer", "description": "What was held for the job; it is charged at most this."},
               "cost_cents": {"type": "integer", "description": "What a finished job was charged."},
               "images": {"type": "array", "description": "A finished image job's outputs, as signed URLs that expire.",
                 "items": {"type": "object", "properties": {"url": {"type": "string"}}}}
             },
             "required": ["job_id", "status"]}""";
}
