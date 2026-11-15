package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ImageMetadataTest;

/**
 * {@link ChatImages} with the production rule for destinations: private
 * addresses are not allowed. No test here opens a connection to anything.
 */
class ChatImagesTest {

    private static final URI API = URI.create("https://api.godance.test/v1/chat/completions");
    private static final UUID USER = UUID.randomUUID();

    private final InputService inputs = mock(InputService.class);
    private final ChatImages images = new ChatImages(new WebhookDestinations(false), inputs);

    // ===== An https URL: public addresses only ==========================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/cat.png",                    // loopback
            "https://localhost/cat.png",
            "https://[::1]/cat.png",
            "https://10.0.0.5/cat.png",                     // private networks
            "https://192.168.1.10/cat.png",
            "https://172.16.0.1/cat.png",
            "https://169.254.169.254/latest/meta-data/",    // the cloud metadata address
            "https://100.64.0.1/cat.png",                   // carrier-grade NAT
            "https://0.0.0.0/cat.png",
            "https://this-host-does-not-exist.invalid/cat.png"})
    void resolve_givenAnAddressThatIsNotPublic_expects400ImageFetchFailedWithoutConnecting(String url) {
        assertRefused(url, 400, ChatImageRefusedException.CODE_IMAGE_FETCH_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/cat.png",                   // not https
            "ftp://example.com/cat.png",
            "file:///etc/passwd",
            "gopher://example.com/",
            "//example.com/cat.png",
            "/v1/files/3f0da8ba-d164-49fb-b832-8e80292bedc1/content",   // relative: not this API's address
            "cat.png",
            "https://user:secret@example.com/cat.png",      // credentials in the URL
            "https://exa mple.com/cat.png",
            "   "})
    void resolve_givenSomethingThatIsNotOneOfTheThreeForms_expects400InvalidImageUrl(String url) {
        assertRefused(url, 400, ChatImageRefusedException.CODE_INVALID_IMAGE_URL);
    }

    @Test
    void resolve_givenAnErrorAboutAUrl_expectsTheUrlNeverRepeated() {
        String secret = "https://127.0.0.1/cat.png?token=do-not-repeat-me";

        assertThatThrownBy(() -> images.resolve(USER, secret, null, API).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ChatImageRefusedException.class, e -> {
                    assertThat(e.getReason()).doesNotContain("do-not-repeat-me").doesNotContain("127.0.0.1");
                    assertThat(String.valueOf(e.getBody().getDetail())).doesNotContain("do-not-repeat-me");
                });
    }

    // ===== A data URL ===================================================

    @Test
    void resolve_givenADataUrl_expectsTheImageWithItsTypeFromTheBytesAndItsMetadataStripped() {
        byte[] photo = ImageMetadataTest.fixture("gps.jpg");
        // Declared as a PNG; it is a JPEG, and that is what it is sent as.
        String url = "data:image/png;base64," + Base64.getEncoder().encodeToString(photo);

        ImagePart part = images.resolve(USER, url, "high", API).block(Duration.ofSeconds(5));

        assertThat(part.mediaType()).isEqualTo("image/jpeg");
        assertThat(part.detail()).isEqualTo("high");
        assertThat(part.data()).isEqualTo(ImageMetadata.strip(photo).bytes());
        assertThat(ImageMetadataTest.count(part.data(), "GPSCANARY")).isZero();
        verifyNoInteractions(inputs);
    }

    @Test
    void resolve_givenADataUrlWithLineBreaksInItsBase64_expectsItDecoded() {
        byte[] image = ImageMetadataTest.fixture("plain.png");
        String wrapped = Base64.getMimeEncoder(40, "\n".getBytes()).encodeToString(image);

        ImagePart part = images.resolve(USER, "DATA:image/png;charset=utf-8;base64," + wrapped, null, API)
                .block(Duration.ofSeconds(5));

        assertThat(part.data()).isEqualTo(image);
        assertThat(part.detail()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "data:image/png,not-base64-at-all",             // no ;base64
            "data:image/png;base64",                        // no comma
            "data:image/png;base64,!!!not base64!!!",
            "data:,"})
    void resolve_givenADataUrlThatIsNotBase64_expects400InvalidImageUrl(String url) {
        assertRefused(url, 400, ChatImageRefusedException.CODE_INVALID_IMAGE_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"picture.gif", "animated.webp", "animated.png"})
    void resolve_givenADataUrlThatIsNotAStillPngJpegOrWebp_expects400ImageTypeUnsupported(String fixture) {
        String url = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(ImageMetadataTest.fixture(fixture));

        assertRefused(url, 400, ChatImageRefusedException.CODE_IMAGE_TYPE_UNSUPPORTED);
    }

    @Test
    void resolve_givenADataUrlOfTextOrOfNothing_expects400ImageTypeUnsupported() {
        assertRefused("data:image/png;base64," + Base64.getEncoder().encodeToString("<svg/>".getBytes()),
                400, ChatImageRefusedException.CODE_IMAGE_TYPE_UNSUPPORTED);
        assertRefused("data:image/png;base64,", 400, ChatImageRefusedException.CODE_IMAGE_TYPE_UNSUPPORTED);
    }

    @Test
    void resolve_givenADetailThatIsNotOneOfOpenAis_expects400InvalidImageUrl() {
        String url = "data:image/png;base64," + Base64.getEncoder().encodeToString(ImageMetadataTest.fixture("plain.png"));

        assertThatThrownBy(() -> images.resolve(USER, url, "ultra", API).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ChatImageRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo(ChatImageRefusedException.CODE_INVALID_IMAGE_URL));
        for (String detail : new String[] {"auto", "low", "high", "original"}) {
            assertThat(images.resolve(USER, url, detail, API).block(Duration.ofSeconds(5)).detail()).isEqualTo(detail);
        }
    }

    // ===== This API's own file address ==================================

    @Test
    void ownFileId_givenThisApisContentAddress_expectsTheFileId() {
        UUID id = UUID.randomUUID();

        assertThat(ChatImages.ownFileId(URI.create("https://api.godance.test/v1/files/" + id + "/content"), API))
                .isEqualTo(id);
        assertThat(ChatImages.ownFileId(URI.create("HTTPS://API.GODANCE.TEST:443/v1/files/" + id + "/content"), API))
                .isEqualTo(id);
        assertThat(ChatImages.ownFileId(URI.create("http://localhost:8080/v1/files/" + id + "/content"),
                URI.create("http://localhost:8080/v1/chat/completions"))).isEqualTo(id);
    }

    @Test
    void ownFileId_givenAnythingElse_expectsNull() {
        UUID id = UUID.randomUUID();
        String path = "/v1/files/" + id + "/content";

        for (String url : new String[] {
                "https://evil.example" + path,                         // another host
                "https://api.godance.test.evil.example" + path,
                "http://api.godance.test" + path,                      // another scheme
                "https://api.godance.test:8443" + path,                // another port
                "https://api.godance.test" + path + "?x=1",            // a query
                "https://api.godance.test" + path + "/",               // more path
                "https://api.godance.test/v1/files/" + id,             // the file, not its content
                "https://api.godance.test/v1/files/not-an-id/content",
                "https://api.godance.test/v2" + path.substring(3),
                "https://api.godance.test/v1/files/" + id + "/../" + id + "/content"}) {
            assertThat(ChatImages.ownFileId(URI.create(url), API)).as(url).isNull();
        }
        assertThat(ChatImages.ownFileId(URI.create("https://api.godance.test" + path), null)).isNull();
    }

    private void assertRefused(String url, int status, String code) {
        assertThatThrownBy(() -> images.resolve(USER, url, null, API).block(Duration.ofSeconds(8)))
                .isInstanceOfSatisfying(ChatImageRefusedException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(status);
                    assertThat(e.code()).isEqualTo(code);
                });
    }
}
