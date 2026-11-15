package ai.godance.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.config.InputsProperties;
import ai.godance.config.RateLimitProperties.Limit;
import ai.godance.domain.InputFile;
import ai.godance.security.RateLimiter;
import ai.godance.service.InputRefusedException;
import ai.godance.service.InputService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Taking in one uploaded image, the same way wherever it arrives:
 * {@code POST /v1/files}, or a part of a multipart {@code POST
 * /v1/images/edits}. The rate limits and brief 01's upload checks are here
 * once, so no route is a way around them.
 */
@Component
public class InputUploadIntake {

    static final Duration UPLOAD_LIMIT_WINDOW = Duration.ofHours(1);

    private final InputService inputs;
    private final RateLimiter limiter;
    private final InputsProperties props;

    public InputUploadIntake(InputService inputs, RateLimiter limiter, InputsProperties props) {
        this.inputs = inputs;
        this.limiter = limiter;
        this.props = props;
    }

    /**
     * Admits one more upload by this account from this address: the upload
     * limits per address and per account, then whether the account may
     * upload at all. Call it once for each image, before reading it.
     */
    public Mono<Void> admit(UUID userId, String ip) {
        return limiter.check("input-upload-ip", ip, new Limit(props.uploadLimitPerIp(), UPLOAD_LIMIT_WINDOW))
                .then(limiter.check("input-upload-account", userId.toString(),
                        new Limit(props.uploadLimitPerAccount(), UPLOAD_LIMIT_WINDOW)))
                .then(Mono.fromRunnable(() -> inputs.requireMayUpload(userId, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /**
     * Reads the part, no further than one byte past the limit, and stores it
     * as the account's input file after the upload checks. A part that is
     * too long errors as {@link InputUploads#isTooLarge} recognises; the
     * caller turns that into {@link #tooLarge} once, for the whole request.
     */
    public Mono<InputFile> store(UUID userId, String ip, Part part) {
        return InputUploads.read(part, inputs.maxBytes() + 1)
                .flatMap(bytes -> Mono.fromCallable(() -> inputs.upload(userId, bytes, ip))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** The refusal for an upload that was too long, recorded as any refused upload is. */
    public InputRefusedException tooLarge(UUID userId, String ip) {
        return inputs.tooLarge(userId, ip);
    }

    /** The address the request came from, as the upload limits and the audit entries count it. */
    public static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
