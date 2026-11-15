package ai.godance.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ImageMetadata.InvalidImageException;
import ai.godance.storage.ImageMetadata.Stripped;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Turns the {@code url} of an image part in a chat request into the image
 * itself (round 11 brief 02). Three forms are accepted and nothing else:
 * <ul>
 *   <li><b>a data URL</b>: decoded here;</li>
 *   <li><b>an https URL</b>: fetched through {@link WebhookDestinations}'
 *       public-only client, which refuses private addresses at connect
 *       time and follows no redirect, with a size limit, a 10-second limit
 *       and an image content type required;</li>
 *   <li><b>{@code <this API>/v1/files/{id}/content}</b>: one of the
 *       caller's own input files, read from storage through
 *       {@link InputService}. It is never fetched over the network.</li>
 * </ul>
 * Whatever the form, the type is read from the bytes (PNG, JPEG or WebP,
 * still images only) and the metadata is stripped before the image goes
 * anywhere: a provider is not sent where a photo was taken.
 *
 * <p>Nothing here stores, logs or audits an image, and no error repeats a
 * URL: a data URL is the image, and an https URL can carry a token.
 */
@Service
public class ChatImages {

    /** The most images one chat request may carry. */
    public static final int MAX_IMAGES = 10;
    /** The largest one image may be, however it arrives. */
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    /** OpenAI's values. The other providers take no such setting and are not sent one. */
    private static final Set<String> DETAILS = Set.of("auto", "low", "high", "original");
    private static final Pattern DATA_URL = Pattern.compile("^data:([^;,]*)(;[^,]*)?,", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern FILE_CONTENT_PATH = Pattern.compile(
            "^/v1/files/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/content$");

    private final WebhookDestinations destinations;
    private final InputService inputs;

    public ChatImages(WebhookDestinations destinations, InputService inputs) {
        this.destinations = destinations;
        this.inputs = inputs;
    }

    /**
     * The image a part's {@code url} names.
     *
     * @param apiBase the address this request reached the API at, which is
     *                how one of the caller's own files is recognised
     * @param detail  OpenAI's {@code detail}: {@code auto}, {@code low},
     *                {@code high}, {@code original} or null
     */
    public Mono<ImagePart> resolve(UUID userId, String url, String detail, URI apiBase) {
        if (detail != null && !DETAILS.contains(detail)) {
            return Mono.error(ChatImageRefusedException.invalidUrl(
                    "image_url.detail must be auto, low, high or original"));
        }
        if (url == null || url.isBlank()) {
            return Mono.error(ChatImageRefusedException.invalidUrl("an image part needs image_url.url"));
        }
        String trimmed = url.trim();
        Mono<byte[]> bytes;
        if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            bytes = Mono.fromCallable(() -> decodeDataUrl(trimmed));
        } else {
            URI uri = parse(trimmed);
            UUID fileId = ownFileId(uri, apiBase);
            bytes = fileId != null
                    ? Mono.fromCallable(() -> inputs.content(userId, fileId).bytes()).subscribeOn(Schedulers.boundedElastic())
                    : fetch(uri);
        }
        return bytes.map(raw -> checked(raw, detail));
    }

    // ===== A data URL ==================================================

    private static byte[] decodeDataUrl(String url) {
        Matcher m = DATA_URL.matcher(url);
        if (!m.find() || m.group(2) == null || !m.group(2).toLowerCase(Locale.ROOT).contains(";base64")) {
            throw ChatImageRefusedException.invalidUrl(
                    "a data URL must be base64: data:image/png;base64,…");
        }
        // The largest image, as base64, with room for line breaks.
        if (url.length() - m.end() > (MAX_IMAGE_BYTES / 3L + 1) * 4 + 1024) {
            throw ChatImageRefusedException.tooLarge(MAX_IMAGE_BYTES / (1024 * 1024));
        }
        try {
            // Line breaks and spaces are allowed in the data; anything else that is not base64 is refused.
            return Base64.getDecoder().decode(WHITESPACE.matcher(url.substring(m.end())).replaceAll(""));
        } catch (IllegalArgumentException e) {
            throw ChatImageRefusedException.invalidUrl("the data URL is not valid base64");
        }
    }

    // ===== One of the caller's own files ===============================

    /**
     * The file id when {@code uri} is this API's own
     * {@code /v1/files/{id}/content}: the same scheme, host and port the
     * request came to, that exact path and nothing after it. Null otherwise.
     */
    static UUID ownFileId(URI uri, URI apiBase) {
        if (apiBase == null || uri.getHost() == null || apiBase.getHost() == null) return null;
        if (!uri.getHost().equalsIgnoreCase(apiBase.getHost())) return null;
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase(apiBase.getScheme())) return null;
        if (port(uri) != port(apiBase)) return null;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) return null;
        Matcher m = FILE_CONTENT_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        return m.matches() ? UUID.fromString(m.group(1)) : null;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    // ===== An https URL ================================================

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw ChatImageRefusedException.invalidUrl(
                        "image_url.url must be a data URL, an https URL, or the content address of one of your files");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw ChatImageRefusedException.invalidUrl("image_url.url is not a valid URL");
        }
    }

    private Mono<byte[]> fetch(URI uri) {
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        // http only where private destinations are allowed at all, which is a test setting.
        if (!https && !(destinations.allowsPrivate() && "http".equalsIgnoreCase(uri.getScheme()))) {
            return Mono.error(ChatImageRefusedException.invalidUrl(
                    "image_url.url must be a data URL, an https URL, or the content address of one of your files"));
        }
        if (uri.getRawUserInfo() != null) {
            return Mono.error(ChatImageRefusedException.invalidUrl("image_url.url must not carry credentials"));
        }
        return Mono.fromRunnable(() -> requirePublic(uri))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> destinations.client().get().uri(uri)
                        .header(HttpHeaders.ACCEPT, "image/png, image/jpeg, image/webp")
                        .exchangeToMono(ChatImages::body)))
                .timeout(FETCH_TIMEOUT)
                .onErrorMap(e -> !(e instanceof ResponseStatusException), ChatImages::fetchFailure);
    }

    /** The submit-time check: every address the host resolves to must be public. The client checks again when it connects. */
    private void requirePublic(URI uri) {
        try {
            destinations.requireAllowed(uri.toString());
        } catch (ResponseStatusException e) {
            throw ChatImageRefusedException.fetchFailed(
                    "The image could not be fetched: its address does not resolve or is not public.");
        }
    }

    private static Mono<byte[]> body(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            // A redirect lands here too: none is followed.
            int status = response.statusCode().value();
            return response.releaseBody().then(Mono.error(ChatImageRefusedException.fetchFailed(
                    status >= 300 && status < 400
                            ? "The image could not be fetched: its address redirects, and redirects are not followed."
                            : "The image could not be fetched: its server answered " + status + ".")));
        }
        MediaType type = response.headers().contentType().orElse(null);
        if (type == null || !"image".equalsIgnoreCase(type.getType())) {
            return response.releaseBody().then(Mono.error(ChatImageRefusedException.fetchFailed(
                    "The image could not be fetched: its server did not answer with an image.")));
        }
        if (response.headers().contentLength().orElse(0) > MAX_IMAGE_BYTES) {
            return response.releaseBody().then(Mono.error(
                    ChatImageRefusedException.tooLarge(MAX_IMAGE_BYTES / (1024 * 1024))));
        }
        return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), MAX_IMAGE_BYTES)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0]);
    }

    private static Throwable fetchFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataBufferLimitException) {
                return ChatImageRefusedException.tooLarge(MAX_IMAGE_BYTES / (1024 * 1024));
            }
            if (t instanceof TimeoutException) {
                return ChatImageRefusedException.fetchFailed(
                        "The image could not be fetched: its server took longer than "
                                + FETCH_TIMEOUT.toSeconds() + " seconds.");
            }
        }
        return ChatImageRefusedException.fetchFailed(
                "The image could not be fetched: its address could not be reached or is not public.");
    }

    // ===== The image itself ============================================

    /** The bytes as an image part: within the size limit, a still PNG, JPEG or WebP, with its metadata stripped. */
    private static ImagePart checked(byte[] raw, String detail) {
        if (raw.length > MAX_IMAGE_BYTES) throw ChatImageRefusedException.tooLarge(MAX_IMAGE_BYTES / (1024 * 1024));
        if (ImageMetadata.sniff(raw).isEmpty()) {
            throw ChatImageRefusedException.typeUnsupported("An image must be a PNG, JPEG or WebP image.");
        }
        Stripped stripped;
        try {
            stripped = ImageMetadata.strip(raw);
        } catch (InvalidImageException e) {
            throw ChatImageRefusedException.typeUnsupported("An image could not be read: " + e.getMessage() + ".");
        }
        if (stripped.info().animated()) {
            throw ChatImageRefusedException.typeUnsupported("An image must be a still image, not an animation.");
        }
        return new ImagePart(stripped.bytes(), stripped.info().format().contentType(), detail);
    }
}
