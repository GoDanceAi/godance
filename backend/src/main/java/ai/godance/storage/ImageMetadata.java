package ai.godance.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Reads what kind of image a file is from its own bytes, and removes its
 * metadata without re-encoding it (round 11 brief 01).
 *
 * <p>An uploaded photo can carry where it was taken (EXIF GPS), the device,
 * the time, the author's name (XMP, IPTC), comments, and whole extra
 * pictures appended after the first. None of that is needed to use the
 * image, so {@link #strip} keeps a short list of what a decoder needs and
 * drops the rest:
 * <ul>
 *   <li><b>JPEG:</b> keeps the image's own segments, APP0 (JFIF), APP2 when
 *       it is the colour profile, and APP14 (Adobe, which decoding needs).
 *       Drops every other APP segment (APP1 holds EXIF and XMP, APP13 holds
 *       IPTC), comments, and everything after the image ends.</li>
 *   <li><b>PNG:</b> keeps the critical chunks and the ones that describe
 *       colour and transparency. Drops {@code eXIf}, {@code tEXt},
 *       {@code zTXt}, {@code iTXt}, {@code tIME}, any chunk it does not
 *       know, and everything after {@code IEND}.</li>
 *   <li><b>WebP:</b> keeps the image data, alpha and the colour profile.
 *       Drops {@code EXIF}, {@code XMP }, any chunk it does not know, and
 *       everything after the container ends.</li>
 * </ul>
 * One thing is carried over from EXIF: the orientation, since without it a
 * phone's portrait photo would lie on its side. It is written back as an
 * EXIF block that holds that one value and nothing else.
 *
 * <p>The pixel data is never touched, so the image is bit for bit the one
 * that was uploaded.
 */
public final class ImageMetadata {

    private ImageMetadata() { }

    /** The image formats an input may be. */
    public enum Format {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        WEBP("image/webp", "webp");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() { return contentType; }
        public String extension() { return extension; }
    }

    /**
     * What a file is. {@code width} and {@code height} are as the image is
     * shown: swapped when its orientation turns it a quarter.
     * {@code animated} is true for an animated PNG or WebP.
     */
    public record Info(Format format, int width, int height, boolean animated) {
        public long pixels() {
            return (long) width * height;
        }
    }

    /** A file with its metadata removed, and what it is. */
    public record Stripped(byte[] bytes, Info info) { }

    /** The bytes are not a complete PNG, JPEG or WebP image. */
    public static final class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    /** The format the bytes start as, whatever the upload declared; empty for anything else. */
    public static Optional<Format> sniff(byte[] b) {
        if (b == null) return Optional.empty();
        if (startsWith(b, 0, PNG_SIGNATURE)) return Optional.of(Format.PNG);
        if (b.length >= 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) return Optional.of(Format.JPEG);
        if (b.length >= 12 && isAscii(b, 0, "RIFF") && isAscii(b, 8, "WEBP")) return Optional.of(Format.WEBP);
        return Optional.empty();
    }

    /** What the image is, without changing it. */
    public static Info inspect(byte[] bytes) {
        return strip(bytes).info();
    }

    /**
     * The image without its metadata.
     *
     * @throws InvalidImageException when the bytes are not a complete PNG,
     *         JPEG or WebP image
     */
    public static Stripped strip(byte[] bytes) {
        Format format = sniff(bytes).orElseThrow(() -> new InvalidImageException("not a PNG, JPEG or WebP image"));
        try {
            return switch (format) {
                case JPEG -> stripJpeg(bytes);
                case PNG -> stripPng(bytes);
                case WEBP -> stripWebp(bytes);
            };
        } catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
            // Every read below is bounds-checked; this is the backstop for one that is not.
            throw new InvalidImageException("the " + format.name() + " file is cut short or malformed");
        }
    }

    // ===== JPEG ===========================================================

    private static final int SOI = 0xD8, EOI = 0xD9, SOS = 0xDA, TEM = 0x01, COM = 0xFE;
    private static final int APP0 = 0xE0, APP1 = 0xE1, APP2 = 0xE2, APP14 = 0xEE, APP15 = 0xEF;
    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    private static final byte[] ICC_HEADER = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ADOBE_HEADER = "Adobe".getBytes(StandardCharsets.US_ASCII);

    private static Stripped stripJpeg(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(SOI);
        int pos = 2;
        int width = 0, height = 0, orientation = 1;
        boolean sawScan = false;
        boolean ended = false;

        while (!ended) {
            if (pos >= b.length) {
                // The data stops without an end marker. With a scan behind us the
                // picture is all there, so close it; without one there is no picture.
                if (!sawScan) throw new InvalidImageException("the JPEG file has no image data");
                break;
            }
            if (u8(b, pos) != 0xFF) throw new InvalidImageException("the JPEG file is malformed");
            // Any number of 0xFF fill bytes may come before a marker.
            while (pos + 1 < b.length && u8(b, pos + 1) == 0xFF) pos++;
            if (pos + 1 >= b.length) {
                if (!sawScan) throw new InvalidImageException("the JPEG file has no image data");
                break;
            }
            int marker = u8(b, pos + 1);
            pos += 2;

            if (marker == EOI) {
                ended = true;
                continue;
            }
            if (marker == SOI) throw new InvalidImageException("the JPEG file is malformed");
            if (marker == TEM || (marker >= 0xD0 && marker <= 0xD7)) {
                // Markers that stand alone, with no length after them.
                out.write(0xFF);
                out.write(marker);
                continue;
            }

            requireAvailable(b, pos, 2, "JPEG");
            int length = u16be(b, pos);
            if (length < 2) throw new InvalidImageException("the JPEG file is malformed");
            requireAvailable(b, pos, length, "JPEG");
            int payload = pos + 2;
            int payloadLength = length - 2;

            boolean keep;
            if (marker == APP1) {
                if (startsWith(b, payload, EXIF_HEADER) && payloadLength > EXIF_HEADER.length) {
                    orientation = exifOrientation(b, payload + EXIF_HEADER.length,
                            payloadLength - EXIF_HEADER.length);
                    if (turnsOrFlips(orientation)) {
                        // In the EXIF block's place: one that holds the orientation and nothing else.
                        byte[] tiff = orientationOnlyTiff(orientation);
                        out.write(0xFF);
                        out.write(APP1);
                        writeU16be(out, 2 + EXIF_HEADER.length + tiff.length);
                        out.writeBytes(EXIF_HEADER);
                        out.writeBytes(tiff);
                    }
                }
                keep = false;
            } else if (marker == APP0) {
                keep = true;
            } else if (marker == APP2) {
                keep = startsWith(b, payload, ICC_HEADER);
            } else if (marker == APP14) {
                keep = startsWith(b, payload, ADOBE_HEADER);
            } else if ((marker > APP0 && marker <= APP15) || marker == COM) {
                keep = false;
            } else {
                keep = true;
                if (isStartOfFrame(marker) && width == 0) {
                    if (payloadLength < 5) throw new InvalidImageException("the JPEG file is malformed");
                    height = u16be(b, payload + 1);
                    width = u16be(b, payload + 3);
                }
            }
            if (keep) {
                out.write(0xFF);
                out.write(marker);
                out.write(b, pos, length);
            }
            pos += length;

            if (marker == SOS) {
                sawScan = true;
                int end = endOfScan(b, pos);
                out.write(b, pos, end - pos);
                pos = end;
            }
        }
        if (!sawScan) throw new InvalidImageException("the JPEG file has no image data");
        if (width <= 0 || height <= 0) throw new InvalidImageException("the JPEG file does not say its size");
        out.write(0xFF);
        out.write(EOI);
        boolean quarterTurn = orientation >= 5 && orientation <= 8;
        return new Stripped(out.toByteArray(),
                new Info(Format.JPEG, quarterTurn ? height : width, quarterTurn ? width : height, false));
    }

    /** Start-of-frame markers: C0–CF, except C4 (Huffman tables), C8 (reserved) and CC (arithmetic coding). */
    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    /**
     * Where the entropy-coded data that starts at {@code from} ends: at the
     * next marker that is not part of it. Inside the data, {@code FF 00} is
     * an escaped 0xFF and {@code FF D0}–{@code FF D7} are restart markers.
     */
    private static int endOfScan(byte[] b, int from) {
        int i = from;
        while (i < b.length) {
            if (u8(b, i) != 0xFF) {
                i++;
                continue;
            }
            if (i + 1 >= b.length) return b.length;
            int next = u8(b, i + 1);
            if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
                i += 2;
            } else if (next == 0xFF) {
                i++;
            } else {
                return i;
            }
        }
        return b.length;
    }

    // ===== PNG ============================================================

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Chunks a decoder needs, and the ones that describe colour and transparency. */
    private static final Set<String> PNG_KEPT = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND",
            "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "hIST", "sPLT",
            "cICP", "mDCV", "cLLI");

    private static Stripped stripPng(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.writeBytes(PNG_SIGNATURE);
        int pos = PNG_SIGNATURE.length;
        int width = 0, height = 0, orientation = 1;
        boolean first = true, sawData = false, ended = false, animated = false;

        while (!ended) {
            requireAvailable(b, pos, 12, "PNG");
            long declared = u32be(b, pos);
            if (declared > Integer.MAX_VALUE) throw new InvalidImageException("the PNG file is malformed");
            int length = (int) declared;
            String type = ascii(b, pos + 4, 4);
            int data = pos + 8;
            requireAvailable(b, data, (long) length + 4, "PNG");
            int total = 12 + length;

            if (first) {
                if (!type.equals("IHDR") || length != 13) throw new InvalidImageException("the PNG file is malformed");
                long w = u32be(b, data), h = u32be(b, data + 4);
                if (w < 1 || h < 1 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
                    throw new InvalidImageException("the PNG file does not say its size");
                }
                width = (int) w;
                height = (int) h;
                first = false;
            }
            switch (type) {
                case "IDAT" -> sawData = true;
                case "IEND" -> ended = true;
                case "acTL", "fcTL", "fdAT" -> animated = true;
                case "eXIf" -> orientation = exifOrientation(b, data, length);
                default -> { }
            }
            if (ended && turnsOrFlips(orientation)) {
                // Just before the end, which is a place the chunk may always be:
                // one that holds the orientation and nothing else.
                writePngChunk(out, "eXIf", orientationOnlyTiff(orientation));
            }
            if (PNG_KEPT.contains(type)) {
                out.write(b, pos, total);
            }
            pos += total;
        }
        if (!sawData) throw new InvalidImageException("the PNG file has no image data");
        boolean quarterTurn = orientation >= 5 && orientation <= 8;
        return new Stripped(out.toByteArray(),
                new Info(Format.PNG, quarterTurn ? height : width, quarterTurn ? width : height, animated));
    }

    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        writeU32be(out, data.length);
        out.writeBytes(name);
        out.writeBytes(data);
        writeU32be(out, crc.getValue());
    }

    // ===== WebP ===========================================================

    private static final int WEBP_FLAG_ANIMATION = 0x02, WEBP_FLAG_XMP = 0x04, WEBP_FLAG_EXIF = 0x08;

    /** The chunks that are the image: its data, alpha, colour profile and animation frames. */
    private static final Set<String> WEBP_KEPT = Set.of("VP8 ", "VP8L", "ALPH", "ICCP", "ANIM", "ANMF");

    private static Stripped stripWebp(byte[] b) {
        requireAvailable(b, 0, 12, "WebP");
        long riffSize = u32le(b, 4);
        long end = 8 + riffSize;
        if (riffSize < 4 || end > b.length) throw new InvalidImageException("the WebP file is cut short or malformed");

        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        int pos = 12;
        int width = 0, height = 0, orientation = 1;
        boolean first = true, extended = false, sawData = false, animated = false;
        int extendedFlags = 0;
        byte[] extendedHeader = null;

        while (pos + 8 <= end) {
            String type = ascii(b, pos, 4);
            long declared = u32le(b, pos + 4);
            int data = pos + 8;
            if (data + declared > end) throw new InvalidImageException("the WebP file is cut short or malformed");
            int length = (int) declared;
            int padded = length + (length & 1);

            if (first) {
                first = false;
                switch (type) {
                    case "VP8 " -> {
                        int[] size = lossySize(b, data, length);
                        width = size[0];
                        height = size[1];
                    }
                    case "VP8L" -> {
                        int[] size = losslessSize(b, data, length);
                        width = size[0];
                        height = size[1];
                    }
                    case "VP8X" -> {
                        if (length < 10) throw new InvalidImageException("the WebP file is malformed");
                        extended = true;
                        extendedFlags = u8(b, data);
                        extendedHeader = java.util.Arrays.copyOfRange(b, data, data + length);
                        width = 1 + u24le(b, data + 4);
                        height = 1 + u24le(b, data + 7);
                        animated = (extendedFlags & WEBP_FLAG_ANIMATION) != 0;
                    }
                    default -> throw new InvalidImageException("the WebP file is malformed");
                }
            }
            switch (type) {
                case "VP8 ", "VP8L" -> sawData = true;
                case "ANIM", "ANMF" -> {
                    animated = true;
                    sawData = true;
                }
                case "EXIF" -> {
                    // Some writers put JPEG's "Exif\0\0" before the TIFF block here too.
                    int skip = startsWith(b, data, EXIF_HEADER) && length > EXIF_HEADER.length ? EXIF_HEADER.length : 0;
                    orientation = exifOrientation(b, data + skip, length - skip);
                }
                default -> { }
            }
            if (WEBP_KEPT.contains(type)) {
                writeWebpChunk(body, type, b, data, length);
            }
            // A plain (not extended) file is exactly one image chunk; nothing after it belongs.
            if (!extended) break;
            pos = data + padded;
        }
        if (first || !sawData) throw new InvalidImageException("the WebP file has no image data");

        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 64);
        ByteArrayOutputStream chunks = new ByteArrayOutputStream(body.size() + 64);
        if (extended) {
            boolean keepOrientation = turnsOrFlips(orientation);
            extendedHeader[0] = (byte) ((extendedFlags & ~(WEBP_FLAG_EXIF | WEBP_FLAG_XMP))
                    | (keepOrientation ? WEBP_FLAG_EXIF : 0));
            writeWebpChunk(chunks, "VP8X", extendedHeader, 0, extendedHeader.length);
            chunks.writeBytes(body.toByteArray());
            if (keepOrientation) {
                byte[] tiff = orientationOnlyTiff(orientation);
                writeWebpChunk(chunks, "EXIF", tiff, 0, tiff.length);
            }
        } else {
            chunks.writeBytes(body.toByteArray());
        }
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeU32le(out, 4L + chunks.size());
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(chunks.toByteArray());

        boolean quarterTurn = orientation >= 5 && orientation <= 8;
        return new Stripped(out.toByteArray(),
                new Info(Format.WEBP, quarterTurn ? height : width, quarterTurn ? width : height, animated));
    }

    private static void writeWebpChunk(ByteArrayOutputStream out, String type, byte[] src, int offset, int length) {
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        writeU32le(out, length);
        out.write(src, offset, length);
        if ((length & 1) == 1) out.write(0);
    }

    /** Size of a lossy (VP8) image: a key frame's header carries it after the start code. */
    private static int[] lossySize(byte[] b, int data, int length) {
        if (length < 10 || (u8(b, data) & 1) != 0
                || u8(b, data + 3) != 0x9D || u8(b, data + 4) != 0x01 || u8(b, data + 5) != 0x2A) {
            throw new InvalidImageException("the WebP file is malformed");
        }
        int width = u16le(b, data + 6) & 0x3FFF;
        int height = u16le(b, data + 8) & 0x3FFF;
        if (width < 1 || height < 1) throw new InvalidImageException("the WebP file does not say its size");
        return new int[] {width, height};
    }

    /** Size of a lossless (VP8L) image: 14 bits each, less one, after the signature byte. */
    private static int[] losslessSize(byte[] b, int data, int length) {
        if (length < 5 || u8(b, data) != 0x2F) throw new InvalidImageException("the WebP file is malformed");
        long bits = u32le(b, data + 1);
        int width = 1 + (int) (bits & 0x3FFF);
        int height = 1 + (int) ((bits >> 14) & 0x3FFF);
        return new int[] {width, height};
    }

    // ===== EXIF orientation ===============================================

    private static final int TAG_ORIENTATION = 0x0112, TYPE_SHORT = 3;

    /** Orientations 2–8 turn or mirror the picture; 1 (and anything unknown) shows it as stored. */
    private static boolean turnsOrFlips(int orientation) {
        return orientation >= 2 && orientation <= 8;
    }

    /**
     * The orientation a TIFF (EXIF) block gives, 1–8; 1 when it gives none or
     * cannot be read. Only the first directory is looked at, which is where
     * the tag lives. A block that is malformed is simply not understood: it
     * is dropped either way.
     */
    static int exifOrientation(byte[] b, int offset, int length) {
        if (length < 14 || offset < 0 || offset + length > b.length) return 1;
        boolean little;
        if (u8(b, offset) == 'I' && u8(b, offset + 1) == 'I') little = true;
        else if (u8(b, offset) == 'M' && u8(b, offset + 1) == 'M') little = false;
        else return 1;
        if (tiffU16(b, offset + 2, little) != 42) return 1;
        long directory = tiffU32(b, offset + 4, little);
        if (directory < 8 || directory + 2 > length) return 1;
        int at = offset + (int) directory;
        int entries = tiffU16(b, at, little);
        at += 2;
        for (int i = 0; i < entries; i++, at += 12) {
            if (at + 12 > offset + length) return 1;
            if (tiffU16(b, at, little) != TAG_ORIENTATION) continue;
            if (tiffU16(b, at + 2, little) != TYPE_SHORT || tiffU32(b, at + 4, little) != 1) return 1;
            int value = tiffU16(b, at + 8, little);
            return value >= 1 && value <= 8 ? value : 1;
        }
        return 1;
    }

    /** A TIFF block whose one directory holds the orientation and nothing else. */
    static byte[] orientationOnlyTiff(int orientation) {
        return new byte[] {
                'M', 'M', 0, 42,            // big-endian TIFF
                0, 0, 0, 8,                 // the first directory follows the header
                0, 1,                       // one entry
                0x01, 0x12,                 // Orientation
                0, TYPE_SHORT,
                0, 0, 0, 1,                 // one value
                0, (byte) orientation, 0, 0,
                0, 0, 0, 0                  // no further directory
        };
    }

    private static int tiffU16(byte[] b, int at, boolean little) {
        return little ? u16le(b, at) : u16be(b, at);
    }

    private static long tiffU32(byte[] b, int at, boolean little) {
        return little ? u32le(b, at) : u32be(b, at);
    }

    // ===== Bytes ==========================================================

    private static void requireAvailable(byte[] b, int from, long count, String what) {
        if (from < 0 || from + count > b.length) {
            throw new InvalidImageException("the " + what + " file is cut short or malformed");
        }
    }

    private static boolean startsWith(byte[] b, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (b[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean isAscii(byte[] b, int offset, String text) {
        return startsWith(b, offset, text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String ascii(byte[] b, int offset, int length) {
        return new String(b, offset, length, StandardCharsets.ISO_8859_1);
    }

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16be(byte[] b, int at) {
        return (u8(b, at) << 8) | u8(b, at + 1);
    }

    private static int u16le(byte[] b, int at) {
        return u8(b, at) | (u8(b, at + 1) << 8);
    }

    private static int u24le(byte[] b, int at) {
        return u8(b, at) | (u8(b, at + 1) << 8) | (u8(b, at + 2) << 16);
    }

    private static long u32be(byte[] b, int at) {
        return ((long) u8(b, at) << 24) | (u8(b, at + 1) << 16) | (u8(b, at + 2) << 8) | u8(b, at + 3);
    }

    private static long u32le(byte[] b, int at) {
        return u8(b, at) | (u8(b, at + 1) << 8) | (u8(b, at + 2) << 16) | ((long) u8(b, at + 3) << 24);
    }

    private static void writeU16be(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32be(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static void writeU32le(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }
}
