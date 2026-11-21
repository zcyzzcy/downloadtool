package com.palworld.vd;

/** Check the container signature before a gallery response is saved as media. */
final class MediaProbe {
    private MediaProbe() {}

    static boolean accepts(byte[] b, int n) {
        if (n < 4) return false;
        if ((b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) return true;
        if (n >= 8 && (b[0] & 255) == 137 && text(b, 1, "PNG\r\n\u001a\n")) return true;
        if (text(b, 0, "GIF87a") || text(b, 0, "GIF89a")) return n >= 10;
        if (n >= 12 && text(b, 0, "RIFF") && (text(b, 8, "WEBP") || text(b, 8, "AVI "))) return true;
        // ISO BMFF: MP4/MOV/AVIF/HEIF. A nonzero box size is required.
        if (n >= 12 && (b[0] != 0 || b[1] != 0 || b[2] != 0 || (b[3] & 255) >= 8)
                && (text(b, 4, "ftyp") || text(b, 4, "moov") || text(b, 4, "wide"))) return true;
        if ((b[0] & 255) == 0x1a && (b[1] & 255) == 0x45 && (b[2] & 255) == 0xdf && (b[3] & 255) == 0xa3) return true;
        if (n >= 9 && text(b, 0, "FLV")) return true;
        if (n >= 8 && (text(b, 0, "II*\u0000") || text(b, 0, "MM\u0000*"))) return true;
        return n >= 14 && text(b, 0, "BM");
    }

    private static boolean text(byte[] b, int offset, String value) {
        if (offset + value.length() > b.length) return false;
        for (int i = 0; i < value.length(); i++) if ((b[offset + i] & 255) != value.charAt(i)) return false;
        return true;
    }
}
