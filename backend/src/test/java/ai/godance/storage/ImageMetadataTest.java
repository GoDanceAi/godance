package ai.godance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ai.godance.storage.ImageMetadata.Format;
import ai.godance.storage.ImageMetadata.Info;
import ai.godance.storage.ImageMetadata.InvalidImageException;
import ai.godance.storage.ImageMetadata.Stripped;

/**
 * {@link ImageMetadata}: the type read from the bytes, the size, and the
 * metadata removed without touching the picture.
 *
 * <p>The fixtures in {@code src/test/resources/inputs} are real files made
 * by an ordinary encoder ({@code make_fixtures.py}, beside them). The
 * {@code gps.*} ones carry GPS coordinates in EXIF and XMP, a device name, a
 * time, comments, a colour profile and bytes appended after the image, each
 * marked with the word {@code GPSCANARY} so a test can look for it.
 */
public class ImageMetadataTest {

    private static final String CANARY = "GPSCANARY";

    // ===== The type comes from the bytes ================================

    @Test
    void sniff_givenEachFormat_expectsItFromTheBytes() {
        assertThat(ImageMetadata.sniff(fixture("gps.jpg"))).contains(Format.JPEG);
        assertThat(ImageMetadata.sniff(fixture("gps.png"))).contains(Format.PNG);
        assertThat(ImageMetadata.sniff(fixture("gps.webp"))).contains(Format.WEBP);
    }

    @Test
    void sniff_givenAnythingElse_expectsNothing() {
        assertThat(ImageMetadata.sniff(fixture("picture.gif"))).isEmpty();
        assertThat(ImageMetadata.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageMetadata.sniff("%PDF-1.7".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageMetadata.sniff(new byte[0])).isEmpty();
        assertThat(ImageMetadata.sniff(null)).isEmpty();
        assertThatThrownBy(() -> ImageMetadata.strip(fixture("picture.gif")))
                .isInstanceOf(InvalidImageException.class);
    }

    // ===== JPEG ==========================================================

    @Test
    void strip_givenJpegWithGpsExif_expectsNoMetadataAndTheSamePicture() throws IOException {
        byte[] original = fixture("gps.jpg");
        assertThat(count(original, CANARY)).isGreaterThanOrEqualTo(6);   // EXIF, XMP, IPTC, comment, MPF, trailer

        Stripped stripped = ImageMetadata.strip(original);

        assertThat(count(stripped.bytes(), CANARY)).isZero();
        assertThat(stripped.bytes().length).isLessThan(original.length);
        // Nothing the image does not need is left: one EXIF block of 34 bytes (the orientation), no XMP,
        // no IPTC, no comment, no second picture.
        List<int[]> segments = jpegSegments(stripped.bytes());
        assertThat(segments.stream().filter(s -> s[0] == 0xE1)).singleElement()
                .satisfies(app1 -> assertThat(app1[2]).isEqualTo(2 + 6 + 26));
        assertThat(segments.stream().map(s -> s[0]))
                .doesNotContain(0xED, 0xFE)
                .contains(0xE0, 0xE2);                                    // JFIF and the colour profile stay
        assertThat(count(stripped.bytes(), "ICC_PROFILE")).isEqualTo(1);
        assertThat(count(stripped.bytes(), "MPF")).isZero();
        assertThat(count(stripped.bytes(), "http://ns.adobe.com/xap/1.0/")).isZero();
        // It ends where the first picture ends.
        assertThat(tail(stripped.bytes(), 2)).containsExactly((byte) 0xFF, (byte) 0xD9);
        assertThat(count(stripped.bytes(), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isEqualTo(1);
        // The picture is the one that was uploaded, decoded pixel for pixel.
        assertSamePixels(original, stripped.bytes());
    }

    @Test
    void strip_givenJpegTurnedAQuarter_expectsTheOrientationKeptAndTheSizeAsShown() {
        Stripped stripped = ImageMetadata.strip(fixture("gps.jpg"));     // stored 64 × 48, orientation 6

        assertThat(stripped.info()).isEqualTo(new Info(Format.JPEG, 48, 64, false));
        // The EXIF block that is left says orientation 6 and nothing else.
        int[] app1 = jpegSegments(stripped.bytes()).stream().filter(s -> s[0] == 0xE1).findFirst().orElseThrow();
        byte[] tiff = Arrays.copyOfRange(stripped.bytes(), app1[1] + 2 + 6, app1[1] + app1[2]);
        assertThat(tiff).isEqualTo(ImageMetadata.orientationOnlyTiff(6));
        assertThat(ImageMetadata.exifOrientation(tiff, 0, tiff.length)).isEqualTo(6);
        // Stripping what was already stripped changes nothing.
        assertThat(ImageMetadata.strip(stripped.bytes()).bytes()).isEqualTo(stripped.bytes());
    }

    @Test
    void strip_givenProgressiveJpegWithRestartMarkers_expectsEveryScanKept() throws IOException {
        byte[] original = fixture("progressive.jpg");

        Stripped stripped = ImageMetadata.strip(original);

        assertThat(stripped.info()).isEqualTo(new Info(Format.JPEG, 96, 64, false));
        assertThat(count(stripped.bytes(), CANARY)).isZero();
        // Orientation 1 is the default, so no EXIF block is written back at all.
        assertThat(jpegSegments(stripped.bytes()).stream().map(s -> s[0])).doesNotContain(0xE1);
        assertThat(jpegSegments(stripped.bytes()).stream().filter(s -> s[0] == 0xDA).count())
                .isEqualTo(jpegSegments(original).stream().filter(s -> s[0] == 0xDA).count())
                .isGreaterThan(1);
        assertSamePixels(original, stripped.bytes());
    }

    @Test
    void strip_givenJpegWithNoMetadata_expectsItUnchanged() {
        byte[] original = fixture("plain.jpg");
        assertThat(ImageMetadata.strip(original).bytes()).isEqualTo(original);
        assertThat(ImageMetadata.inspect(original)).isEqualTo(new Info(Format.JPEG, 64, 48, false));
    }

    // ===== PNG ===========================================================

    @Test
    void strip_givenPngWithGpsExifAndText_expectsNoMetadataAndTheSamePicture() throws IOException {
        byte[] original = fixture("gps.png");
        assertThat(pngChunks(original).keySet())
                .contains("eXIf", "tEXt", "zTXt", "iTXt", "tIME", "iCCP", "gdPv");

        Stripped stripped = ImageMetadata.strip(original);

        Map<String, Integer> chunks = pngChunks(stripped.bytes());
        assertThat(chunks.keySet()).containsExactly("IHDR", "iCCP", "IDAT", "eXIf", "IEND");
        assertThat(count(stripped.bytes(), CANARY)).isZero();
        assertThat(count(stripped.bytes(), "xmpmeta")).isZero();
        // The eXIf chunk that is left holds the orientation and nothing else.
        assertThat(chunks.get("eXIf")).isEqualTo(26);
        assertThat(stripped.info()).isEqualTo(new Info(Format.PNG, 48, 64, false));   // orientation 6
        // Nothing after the end of the image.
        assertThat(new String(tail(stripped.bytes(), 8), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("IEND");
        assertSamePixels(original, stripped.bytes());
        assertThat(ImageMetadata.strip(stripped.bytes()).bytes()).isEqualTo(stripped.bytes());
    }

    @Test
    void strip_givenPngWithNoMetadata_expectsItUnchanged() {
        byte[] original = fixture("plain.png");
        assertThat(ImageMetadata.strip(original).bytes()).isEqualTo(original);
        assertThat(ImageMetadata.inspect(original)).isEqualTo(new Info(Format.PNG, 64, 48, false));
    }

    @Test
    void inspect_givenAnimatedPng_expectsItSaidToBeAnimated() {
        assertThat(ImageMetadata.inspect(fixture("animated.png")).animated()).isTrue();
    }

    // ===== WebP ==========================================================

    @Test
    void strip_givenWebpWithGpsExifAndXmp_expectsNoMetadataAndTheSameImageData() {
        byte[] original = fixture("gps.webp");
        assertThat(webpChunks(original).keySet()).contains("VP8X", "ICCP", "VP8 ", "EXIF", "XMP ");

        Stripped stripped = ImageMetadata.strip(original);

        Map<String, byte[]> chunks = webpChunks(stripped.bytes());
        assertThat(chunks.keySet()).containsExactly("VP8X", "ICCP", "VP8 ", "EXIF");
        assertThat(count(stripped.bytes(), CANARY)).isZero();
        assertThat(count(stripped.bytes(), "xmpmeta")).isZero();
        // The EXIF chunk that is left holds the orientation and nothing else; the header says so.
        assertThat(chunks.get("EXIF")).isEqualTo(ImageMetadata.orientationOnlyTiff(6));
        assertThat(chunks.get("VP8X")[0] & 0x08).as("EXIF flag").isEqualTo(0x08);
        assertThat(chunks.get("VP8X")[0] & 0x04).as("XMP flag").isZero();
        assertThat(chunks.get("VP8X")[0] & 0x20).as("colour profile flag").isEqualTo(0x20);
        assertThat(stripped.info()).isEqualTo(new Info(Format.WEBP, 48, 64, false));  // orientation 6
        // The image data and the colour profile are byte for byte the ones uploaded, so it decodes the same.
        assertThat(chunks.get("VP8 ")).isEqualTo(webpChunks(original).get("VP8 "));
        assertThat(chunks.get("ICCP")).isEqualTo(webpChunks(original).get("ICCP"));
        // The container says its own length, and nothing follows it.
        assertThat(riffSize(stripped.bytes())).isEqualTo(stripped.bytes().length - 8);
        assertThat(ImageMetadata.strip(stripped.bytes()).bytes()).isEqualTo(stripped.bytes());
    }

    @Test
    void strip_givenWebpWithAlphaAndUprightExif_expectsNoExifAtAll() {
        byte[] original = fixture("alpha.webp");
        assertThat(count(original, CANARY)).isPositive();

        Stripped stripped = ImageMetadata.strip(original);

        Map<String, byte[]> chunks = webpChunks(stripped.bytes());
        assertThat(chunks.keySet()).doesNotContain("EXIF", "XMP ");
        assertThat(chunks.get("VP8X")[0] & 0x0C).as("EXIF and XMP flags").isZero();
        assertThat(count(stripped.bytes(), CANARY)).isZero();
        assertThat(stripped.info()).isEqualTo(new Info(Format.WEBP, 64, 48, false));
        Map<String, byte[]> before = webpChunks(original);
        before.keySet().removeAll(List.of("VP8X", "EXIF", "XMP "));
        Map<String, byte[]> after = new LinkedHashMap<>(chunks);
        after.remove("VP8X");
        assertThat(after.keySet()).isEqualTo(before.keySet());
        after.forEach((type, data) -> assertThat(data).as(type).isEqualTo(before.get(type)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain.webp", "lossless.webp"})
    void strip_givenPlainWebp_expectsItUnchanged(String name) {
        byte[] original = fixture(name);
        assertThat(ImageMetadata.strip(original).bytes()).isEqualTo(original);
        assertThat(ImageMetadata.inspect(original)).isEqualTo(new Info(Format.WEBP, 64, 48, false));
    }

    @Test
    void inspect_givenAnimatedWebp_expectsItSaidToBeAnimated() {
        assertThat(ImageMetadata.inspect(fixture("animated.webp")).animated()).isTrue();
    }

    // ===== Files that are not whole =====================================

    @ParameterizedTest
    @ValueSource(strings = {"gps.jpg", "gps.png", "gps.webp", "plain.webp", "lossless.webp", "progressive.jpg"})
    void strip_givenFileCutShortAnywhere_expectsARefusalOrAWholeImageNeverACrash(String name) {
        byte[] original = fixture(name);
        for (int length = 0; length < original.length; length++) {
            byte[] cut = Arrays.copyOf(original, length);
            try {
                Stripped stripped = ImageMetadata.strip(cut);
                // Whatever is accepted has a size and still carries none of the metadata.
                assertThat(stripped.info().pixels()).isPositive();
                assertThat(count(stripped.bytes(), CANARY)).isZero();
            } catch (InvalidImageException expected) {
                // A file that is not all there is refused, by this exception and no other.
            }
        }
    }

    @Test
    void strip_givenHeaderOnly_expectsARefusal() {
        assertThatThrownBy(() -> ImageMetadata.strip(Arrays.copyOf(fixture("gps.png"), 8)))
                .isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> ImageMetadata.strip(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}))
                .isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> ImageMetadata.strip("RIFF\4\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void exifOrientation_givenGarbage_expectsUpright() {
        byte[] garbage = "IIxx this is not a TIFF block at all".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(ImageMetadata.exifOrientation(garbage, 0, garbage.length)).isEqualTo(1);
        assertThat(ImageMetadata.exifOrientation(new byte[4], 0, 4)).isEqualTo(1);
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] tiff = ImageMetadata.orientationOnlyTiff(orientation);
            assertThat(ImageMetadata.exifOrientation(tiff, 0, tiff.length)).isEqualTo(orientation);
        }
    }

    // ===== Helpers =======================================================

    /** A fixture from {@code src/test/resources/inputs}. */
    public static byte[] fixture(String name) {
        try (InputStream in = ImageMetadataTest.class.getResourceAsStream("/inputs/" + name)) {
            if (in == null) throw new IllegalStateException("no fixture " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How many times {@code needle} occurs in {@code haystack}. */
    public static int count(byte[] haystack, String needle) {
        return count(haystack, needle.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static int count(byte[] haystack, byte[] needle) {
        int found = 0;
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            found++;
        }
        return found;
    }

    private static byte[] tail(byte[] b, int n) {
        return Arrays.copyOfRange(b, b.length - n, b.length);
    }

    /** Decodes both and compares every pixel: the stripped file is still an image, and the same one. */
    public static void assertSamePixels(byte[] before, byte[] after) throws IOException {
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(before));
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(after));
        assertThat(a).as("the original decodes").isNotNull();
        assertThat(b).as("the stripped file decodes").isNotNull();
        assertThat(b.getWidth()).isEqualTo(a.getWidth());
        assertThat(b.getHeight()).isEqualTo(a.getHeight());
        int[] pa = a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth());
        int[] pb = b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
        assertThat(pb).isEqualTo(pa);
    }

    /** The marker segments of a JPEG up to its first scan: {marker, offset of the length, length}. */
    private static List<int[]> jpegSegments(byte[] b) {
        List<int[]> segments = new ArrayList<>();
        int pos = 2;
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) break;
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xD9) break;
            if (marker >= 0xD0 && marker <= 0xD7) {
                pos += 2;
                continue;
            }
            int length = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            segments.add(new int[] {marker, pos + 2, length});
            pos += 2 + length;
            if (marker == 0xDA) {
                // Skip the scan's data to the next real marker.
                while (pos + 1 < b.length) {
                    if ((b[pos] & 0xFF) == 0xFF) {
                        int next = b[pos + 1] & 0xFF;
                        if (next != 0x00 && next != 0xFF && !(next >= 0xD0 && next <= 0xD7)) break;
                    }
                    pos++;
                }
            }
        }
        return segments;
    }

    /** The chunks of a PNG in order, each with the length of its data (the last of a kind wins). */
    private static Map<String, Integer> pngChunks(byte[] b) {
        Map<String, Integer> chunks = new LinkedHashMap<>();
        int pos = 8;
        while (pos + 12 <= b.length) {
            int length = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            String type = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
            chunks.put(type, length);
            pos += 12 + length;
            if (type.equals("IEND")) break;
        }
        return chunks;
    }

    /** The chunks of a WebP in order, each with its data. */
    private static Map<String, byte[]> webpChunks(byte[] b) {
        Map<String, byte[]> chunks = new LinkedHashMap<>();
        int end = 8 + riffSize(b);
        int pos = 12;
        while (pos + 8 <= end) {
            String type = new String(b, pos, 4, StandardCharsets.ISO_8859_1);
            int length = (b[pos + 4] & 0xFF) | ((b[pos + 5] & 0xFF) << 8) | ((b[pos + 6] & 0xFF) << 16) | ((b[pos + 7] & 0xFF) << 24);
            chunks.put(type, Arrays.copyOfRange(b, pos + 8, pos + 8 + length));
            pos += 8 + length + (length & 1);
        }
        return chunks;
    }

    private static int riffSize(byte[] b) {
        return (b[4] & 0xFF) | ((b[5] & 0xFF) << 8) | ((b[6] & 0xFF) << 16) | ((b[7] & 0xFF) << 24);
    }
}
