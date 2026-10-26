package com.kaarigarsetu.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds silent 16-bit PCM WAV files of a given shape for tests. */
public final class WavFixtures {
    private WavFixtures() {}

    public static byte[] wav(int durationMs, int rate, int channels, int bits) {
        int data = (int) ((long) rate * channels * (bits / 8) * durationMs / 1000);
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        b.put("data".getBytes()).putInt(data);
        return b.array();
    }
}
