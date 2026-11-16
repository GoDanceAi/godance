package ai.godance.mcp;

import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import ai.godance.service.InputService;
import ai.godance.web.InputUploadIntake;
import ai.godance.web.dto.InputDtos.InputFileView;
import reactor.core.publisher.Mono;

/**
 * {@code upload_input}: an image, sent as base64, stored as the caller's
 * input file, exactly as {@code POST /v1/files} stores one. The checks run in
 * that route's order and answer with its codes: inputs switched on, the
 * upload limits and whether the account may upload ({@code
 * inputs_terms_required} until the person has accepted the terms in the
 * app), the size ({@code input_too_large}, decided before the image is
 * decoded when the base64 alone is too long), then the type, the pixels and
 * the person check in {@link InputService#upload}.
 */
@Component
class InputTools implements McpToolProvider {

    private static final String DATA_URL = "data:";

    private final ToolSupport support;
    private final InputService inputs;
    private final InputUploadIntake intake;

    InputTools(ToolSupport support, InputService inputs, InputUploadIntake intake) {
        this.support = support;
        this.inputs = inputs;
        this.intake = intake;
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(support.spec(support.acting("upload_input", "Upload an input image", DESCRIPTION, INPUT,
                OUTPUT), this::upload));
    }

    private Mono<CallToolResult> upload(McpCaller caller, ToolArgs args) {
        String data = args.requiredText("data");
        // Switched off: inputs_disabled, before anything is counted or read, as the REST route answers.
        inputs.requireEnabled();
        return intake.admit(caller.userId(), caller.clientIp())
                .then(ToolSupport.blocking(() -> decode(caller, data)))
                .flatMap(bytes -> ToolSupport.blocking(() -> inputs.upload(caller.userId(), bytes, caller.clientIp())))
                .map(file -> support.results().success("Stored input file " + file.getId() + " ("
                        + file.getWidth() + "x" + file.getHeight() + ", " + file.getContentType() + "). Use it as "
                        + "{\"file_id\": \"" + file.getId() + "\"} in generate_image images, generate_video "
                        + "first_frame or reference_images, or run_recipe inputs.", InputFileView.from(file)));
    }

    /**
     * The image's bytes. Too long is {@code input_too_large}, recorded as any
     * refused upload is, and decided from the length of the base64 when that
     * alone says so, so an oversized image is never decoded.
     */
    private byte[] decode(McpCaller caller, String data) {
        String base64 = data.trim();
        if (base64.startsWith(DATA_URL)) {
            int comma = base64.indexOf(',');
            if (comma < 0 || !base64.substring(0, comma).endsWith(";base64")) {
                throw ToolArgs.badRequest("data must be base64, or a data: URL with ;base64");
            }
            base64 = base64.substring(comma + 1);
        }
        base64 = base64.replaceAll("\\s", "");
        long longest = (inputs.maxBytes() + 2) / 3 * 4;
        if (base64.length() > longest) throw intake.tooLarge(caller.userId(), caller.clientIp());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw ToolArgs.badRequest("data must be base64, or a data: URL with ;base64");
        }
        if (bytes.length > inputs.maxBytes()) throw intake.tooLarge(caller.userId(), caller.clientIp());
        return bytes;
    }

    static final String DESCRIPTION = """
            Store an image (PNG, JPEG or WebP, as base64 or a data: URL) as an input file of the caller, to \
            use in generate_image (images, mask), generate_video (first_frame, last_frame, reference_images) \
            or run_recipe (inputs) as {"file_id": "…"}. Free. Inputs must be switched on, and the account \
            must have accepted the input terms in the Godance app: inputs_terms_required means the user has \
            to do that there. Images showing people are refused (input_person_not_allowed). The image may \
            be at most the size POST /v1/files allows (input_too_large).""";

    static final String INPUT = """
            {"type": "object",
             "properties": {
               "data": {"type": "string", "description": "The image as base64, or a data: URL such as data:image/png;base64,…"}
             },
             "required": ["data"],
             "additionalProperties": false}""";

    static final String OUTPUT = """
            {"type": "object",
             "properties": {
               "id": {"type": "string", "description": "Use as {\\"file_id\\": id}."},
               "content_type": {"type": "string"},
               "width": {"type": "integer"},
               "height": {"type": "integer"}
             },
             "required": ["id"]}""";
}
