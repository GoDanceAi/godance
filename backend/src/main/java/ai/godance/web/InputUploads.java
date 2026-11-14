package ai.godance.web;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.Part;

import reactor.core.publisher.Mono;

/**
 * Reads an uploaded part into memory, never past a limit (round 11 brief
 * 01). {@code /v1/files} uses it, and so does any route that takes images
 * as multipart parts (image edits, brief 03).
 */
public final class InputUploads {

    private InputUploads() { }

    /**
     * The part's bytes. Errors with {@link DataBufferLimitException} as soon
     * as it turns out to be longer than {@code maxBytes}, without reading the
     * rest. The part's temporary file, if it had one, is deleted either way.
     */
    public static Mono<byte[]> read(Part part, long maxBytes) {
        int limit = (int) Math.min(Integer.MAX_VALUE - 16, maxBytes);
        return DataBufferUtils.join(part.content(), limit)
                .map(InputUploads::toBytes)
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> part.delete().onErrorComplete().thenReturn(bytes))
                .onErrorResume(e -> part.delete().onErrorComplete().then(Mono.error(e)));
    }

    /** Whether an error, at any depth, is a part or a request that was longer than allowed. */
    public static boolean isTooLarge(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof DataBufferLimitException) return true;
        }
        return false;
    }

    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
