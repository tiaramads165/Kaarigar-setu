package com.kaarigarsetu.util;

import com.kaarigarsetu.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static com.kaarigarsetu.support.WavFixtures.wav;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WavValidatorTest {
    private static final int MAX = 2 * 1024 * 1024;

    @Test
    void acceptsAValidWavAndReportsTheRealDuration() {
        assertEquals(2000, WavValidator.validate(wav(2000, 16000, 1, 16), MAX));
    }

    @Test
    void exactlySixtySecondsAt16kMonoFitsInTwoMegabytes() {
        byte[] sixty = wav(60_000, 16000, 1, 16);
        assertTrue(sixty.length < MAX);
        assertDoesNotThrow(() -> WavValidator.validate(sixty, MAX));
        assertThrows(ValidationException.class, () -> WavValidator.validate(wav(61_000, 8000, 1, 16), MAX));
    }

    @Test
    void rejectsTooShortTooLargeEmptyAndNonWav() {
        assertThrows(ValidationException.class, () -> WavValidator.validate(wav(100, 16000, 1, 16), MAX));
        assertThrows(ValidationException.class, () -> WavValidator.validate(wav(2000, 16000, 1, 16), 1000));
        assertThrows(ValidationException.class, () -> WavValidator.validate(new byte[0], MAX));
        assertThrows(ValidationException.class, () -> WavValidator.validate(null, MAX));
        byte[] junk = new byte[5000];
        new Random(1).nextBytes(junk);
        assertThrows(ValidationException.class, () -> WavValidator.validate(junk, MAX));
        byte[] notWave = wav(2000, 16000, 1, 16);
        notWave[8] = 'X';
        assertThrows(ValidationException.class, () -> WavValidator.validate(notWave, MAX));
    }

    @Test
    void rejectsNon16BitPcmAndOddFormats() {
        assertThrows(ValidationException.class, () -> WavValidator.validate(wav(2000, 16000, 1, 8), MAX));
        assertThrows(ValidationException.class, () -> WavValidator.validate(wav(2000, 4000, 1, 16), MAX));
        assertThrows(ValidationException.class, () -> WavValidator.validate(wav(2000, 16000, 3, 16), MAX));
    }

    @Test
    void aHeaderThatLiesAboutTheDataSizeCannotSmuggleInLongAudio() {
        byte[] lying = wav(2000, 16000, 1, 16);
        ByteBuffer.wrap(lying).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1);   // claims ~4 GB of data
        assertEquals(2000, WavValidator.validate(lying, MAX));
    }
}
