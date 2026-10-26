package com.kaarigarsetu.util;

import com.kaarigarsetu.exception.ValidationException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Never trust a client's Content-Type or file name. Accepts only RIFF/WAVE 16-bit PCM audio, checked by magic bytes
 * and a parsed header, within the size and duration limits. Duration comes from the data chunk, not from the client.
 */
public final class WavValidator {
    private static final int MIN_MS = 500;
    private static final int MAX_MS = 60_000;

    private WavValidator() {}

    /** @return the duration in milliseconds. */
    public static int validate(byte[] wav, int maxBytes) {
        if (wav == null || wav.length < 44) throw new ValidationException("Audio is empty or too short");
        if (wav.length > maxBytes) throw new ValidationException("Audio is too large");
        if (!tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) throw new ValidationException("Audio must be a WAV file");
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int format = -1, channels = 0, rate = 0, bits = 0;
        long dataBytes = -1;
        long position = 12;
        while (position + 8 <= wav.length) {
            String id = new String(wav, (int) position, 4, StandardCharsets.US_ASCII);
            long size = buffer.getInt((int) position + 4) & 0xFFFFFFFFL;
            long body = position + 8;
            if (id.equals("fmt ")) {
                if (size < 16 || body + 16 > wav.length) throw new ValidationException("Corrupt WAV header");
                format = buffer.getShort((int) body) & 0xFFFF;
                channels = buffer.getShort((int) body + 2) & 0xFFFF;
                rate = buffer.getInt((int) body + 4);
                bits = buffer.getShort((int) body + 14) & 0xFFFF;
            } else if (id.equals("data")) {
                dataBytes = Math.min(size, wav.length - body);   // a lying size field is clamped to the bytes present
                break;
            }
            position = body + size + (size & 1);
        }
        if (format != 1 || bits != 16) throw new ValidationException("WAV must be 16-bit PCM");
        if (channels < 1 || channels > 2 || rate < 8000 || rate > 48000 || dataBytes <= 0) throw new ValidationException("Unsupported WAV format");
        int durationMs = (int) (dataBytes * 1000 / ((long) rate * channels * 2));
        if (durationMs < MIN_MS) throw new ValidationException("Audio is too short");
        if (durationMs > MAX_MS) throw new ValidationException("Audio is too long");
        return durationMs;
    }

    private static boolean tag(byte[] bytes, int offset, String tag) {
        for (int i = 0; i < 4; i++) if (bytes[offset + i] != (byte) tag.charAt(i)) return false;
        return true;
    }
}
