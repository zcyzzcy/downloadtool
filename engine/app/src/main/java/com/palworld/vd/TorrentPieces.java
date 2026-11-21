package com.palworld.vd;

/** Byte ranges and verified-piece boundaries; independent of Android for regression checks. */
final class TorrentPieces {
    static boolean has(String bits, long piece) {
        if (piece < 0 || piece / 4 >= bits.length()) return false;
        int nibble = Character.digit(bits.charAt((int) (piece / 4)), 16);
        return nibble >= 0 && (nibble & (8 >> (int) (piece % 4))) != 0;
    }

    static long available(String bits, long pieceLength, long offset, long position, long length) {
        if (pieceLength <= 0 || position < 0 || position >= length || offset < 0) return 0;
        long absolute = offset + position;
        if (absolute < offset) return 0;
        long piece = absolute / pieceLength;
        if (!has(bits, piece)) return 0;
        long count = Math.min(length - position, pieceLength - absolute % pieceLength);
        // Bound each read so a huge bitfield cannot monopolize a worker.
        while (count < Math.min(length - position, 262144) && has(bits, ++piece))
            count += Math.min(length - position - count, pieceLength);
        return Math.min(count, 262144);
    }

    /** Single RFC 7233 byte range. Invalid/unsatisfiable ranges must return 416. */
    static long[] range(String value, long length) {
        if (length <= 0) throw new IllegalArgumentException();
        if (value == null) return new long[]{0, length - 1};
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0) throw new IllegalArgumentException();
        String[] parts = value.substring(6).split("-", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        long start, end;
        if (parts[0].isEmpty()) {
            long suffix = Long.parseLong(parts[1]);
            if (suffix <= 0) throw new IllegalArgumentException();
            start = Math.max(0, length - suffix); end = length - 1;
        } else {
            start = Long.parseLong(parts[0]);
            end = parts[1].isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(parts[1]));
        }
        if (start < 0 || start >= length || end < start) throw new IllegalArgumentException();
        return new long[]{start, end};
    }
}
