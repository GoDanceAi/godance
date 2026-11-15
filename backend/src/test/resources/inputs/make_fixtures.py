"""Builds the small image fixtures for the input-file tests. Every file carries
recognisable canary strings in its metadata, so a test can assert they are gone."""
import io, struct, sys, zlib
from PIL import Image, ImageCms, ImageDraw, PngImagePlugin

out = sys.argv[1]

def picture(w=64, h=48, mode="RGB"):
    im = Image.new(mode, (w, h), (30, 90, 160) if mode == "RGB" else (30, 90, 160, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([w // 8, h // 6, w - w // 3, h - h // 4], fill=(230, 180, 40) if mode == "RGB" else (230, 180, 40, 128))
    d.ellipse([w // 2, h // 12, w - w // 10, h // 2], fill=(200, 40, 60) if mode == "RGB" else (200, 40, 60, 255))
    return im

def exif_with_gps(orientation=6):
    ex = Image.Exif()
    ex[0x010F] = "GPSCANARY-MAKE"          # Make
    ex[0x0110] = "GPSCANARY-MODEL"         # Model
    ex[0x0112] = orientation               # Orientation
    ex[0x0132] = "2026:11:14 10:00:00"     # DateTime
    gps = ex.get_ifd(0x8825)
    gps[1] = "N"; gps[2] = (40.0, 42.0, 46.08)
    gps[3] = "W"; gps[4] = (74.0, 0.0, 21.6)
    return ex

XMP = (b'<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/">'
       b'<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description '
       b'xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="40,42.768N" '
       b'exif:GPSLongitude="74,0.36W" exif:UserComment="GPSCANARY-XMP"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>')
ICC = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()

def segment(marker, payload):
    return b"\xff" + bytes([marker]) + struct.pack(">H", len(payload) + 2) + payload

def splice_after_soi(jpeg, extra):
    assert jpeg[:2] == b"\xff\xd8"
    return jpeg[:2] + extra + jpeg[2:]

# ---- JPEG ------------------------------------------------------------------
b = io.BytesIO()
picture().save(b, "JPEG", quality=85, exif=exif_with_gps(6), icc_profile=ICC, xmp=XMP)
jpeg = b.getvalue()
jpeg = splice_after_soi(jpeg,
    segment(0xED, b"Photoshop 3.0\x008BIM\x04\x04\x00\x00\x00\x00\x00\x14GPSCANARY-IPTC......")
    + segment(0xFE, b"GPSCANARY-COMMENT")
    + segment(0xE2, b"MPF\x00GPSCANARY-MPF"))
# A second picture after the first one's end, as phones append (it has its own EXIF).
b2 = io.BytesIO(); picture(16, 12).save(b2, "JPEG", exif=exif_with_gps(1))
open(f"{out}/gps.jpg", "wb").write(jpeg + b2.getvalue() + b"GPSCANARY-TRAILER")

b = io.BytesIO()
picture(96, 64).save(b, "JPEG", quality=80, progressive=True, exif=exif_with_gps(1), restart_marker_blocks=2)
open(f"{out}/progressive.jpg", "wb").write(b.getvalue())

b = io.BytesIO(); picture().save(b, "JPEG", quality=85)
open(f"{out}/plain.jpg", "wb").write(b.getvalue())

# ---- PNG -------------------------------------------------------------------
info = PngImagePlugin.PngInfo()
info.add_text("Comment", "GPSCANARY-TEXT")
info.add_text("Author", "GPSCANARY-ZTXT " * 8, zip=True)
info.add_itxt("XML:com.adobe.xmp", XMP.decode())
b = io.BytesIO()
picture().save(b, "PNG", pnginfo=info, exif=exif_with_gps(6), icc_profile=ICC)
png = b.getvalue()
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
iend = png.rindex(b"IEND") - 4
png = png[:iend] + chunk(b"tIME", struct.pack(">HBBBBB", 2026, 11, 14, 10, 0, 0)) \
      + chunk(b"gdPv", b"GPSCANARY-PRIVATE") + png[iend:] + b"GPSCANARY-TRAILER"
open(f"{out}/gps.png", "wb").write(png)

b = io.BytesIO(); picture(mode="RGBA").save(b, "PNG")
open(f"{out}/plain.png", "wb").write(b.getvalue())

b = io.BytesIO()
frames = [picture(), picture().rotate(180)]
frames[0].save(b, "PNG", save_all=True, append_images=frames[1:], duration=100, loop=0)
open(f"{out}/animated.png", "wb").write(b.getvalue())

# ---- WebP ------------------------------------------------------------------
b = io.BytesIO(); picture().save(b, "WEBP", quality=80, exif=exif_with_gps(6), icc_profile=ICC, xmp=XMP)
open(f"{out}/gps.webp", "wb").write(b.getvalue() + b"GPSCANARY-TRAILER")
b = io.BytesIO(); picture().save(b, "WEBP", quality=80)
open(f"{out}/plain.webp", "wb").write(b.getvalue())
b = io.BytesIO(); picture().save(b, "WEBP", lossless=True)
open(f"{out}/lossless.webp", "wb").write(b.getvalue())
b = io.BytesIO(); picture(mode="RGBA").save(b, "WEBP", quality=80, exif=exif_with_gps(1))
open(f"{out}/alpha.webp", "wb").write(b.getvalue())
b = io.BytesIO()
frames[0].save(b, "WEBP", save_all=True, append_images=frames[1:], duration=100, loop=0)
open(f"{out}/animated.webp", "wb").write(b.getvalue())

# ---- Not images ------------------------------------------------------------
b = io.BytesIO(); picture().save(b, "GIF")
open(f"{out}/picture.gif", "wb").write(b.getvalue())
