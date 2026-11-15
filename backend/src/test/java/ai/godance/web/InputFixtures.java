package ai.godance.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import ai.godance.storage.ImageMetadataTest;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * What the input-file tests share: the image fixtures, the multipart upload
 * as a client sends it, and accepting the input terms.
 */
public final class InputFixtures {

    private InputFixtures() { }

    /** A fixture from {@code src/test/resources/inputs}; the {@code gps.*} ones carry GPS metadata. */
    public static byte[] image(String name) {
        return ImageMetadataTest.fixture(name);
    }

    /** A flat-colour PNG of this size, written by the JDK. */
    public static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** A new adult account: its id and its session's bearer header. */
    public record Signed(UUID id, String bearer) { }

    public static String signup(WebTestClient http, String email) {
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Input Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    /** {@code POST /v1/files} as OpenAI-style clients send it: a part named file, and the purpose. */
    public static WebTestClient.ResponseSpec upload(WebTestClient http, String bearer, byte[] bytes,
                                                    String filename, MediaType declared) {
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("purpose", "generation_input");
        form.part("file", named(bytes, filename)).contentType(declared);
        return http.post().uri("/v1/files")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .exchange();
    }

    /** {@link #upload} with a name and a declared type that match the fixture. */
    public static WebTestClient.ResponseSpec upload(WebTestClient http, String bearer, String fixture) {
        return upload(http, bearer, image(fixture), fixture, declaredType(fixture));
    }

    public static MediaType declaredType(String filename) {
        if (filename.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (filename.endsWith(".jpg")) return MediaType.IMAGE_JPEG;
        if (filename.endsWith(".gif")) return MediaType.IMAGE_GIF;
        return MediaType.parseMediaType("image/webp");
    }

    public static ByteArrayResource named(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    public static WebTestClient.ResponseSpec acceptTerms(WebTestClient http, String bearer, Object version) {
        return http.post().uri("/v1/inputs/terms")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version", version))
                .exchange();
    }

    /** Fetches a signed link the way a browser or a provider would: with no credentials at all. */
    public static HttpResponse<byte[]> fetch(String url) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
