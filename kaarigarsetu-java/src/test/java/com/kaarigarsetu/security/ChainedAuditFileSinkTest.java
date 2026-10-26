package com.kaarigarsetu.security;

import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChainedAuditFileSinkTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    @TempDir Path dir;
    private Path file;
    private ChainedAuditFileSink sink;

    @BeforeEach
    void setUp() {
        file = dir.resolve("audit.log");
        sink = new ChainedAuditFileSink(file, KEY, new MutableClock(Instant.parse("2026-10-07T09:00:00Z")));
    }

    private void fill(int n) throws IOException {
        for (int i = 1; i <= n; i++) sink.append("EVENT " + i);
    }

    private List<String> lines() throws IOException {
        return new ArrayList<>(Files.readAllLines(file));
    }

    @Test
    void emptyAndFreshLogsVerify() throws IOException {
        assertTrue(sink.verify().valid());
        fill(5);
        assertEquals(5, sink.verify().lines());
        assertTrue(sink.verify().valid());
    }

    @Test
    void editingDeletingOrReorderingALineIsDetected() throws IOException {
        fill(6);
        List<String> original = lines();

        List<String> edited = new ArrayList<>(original);
        edited.set(2, edited.get(2).replace("EVENT 3", "FORGED"));
        Files.write(file, edited);
        assertEquals(4, sink.verify().firstBadLine(), "the line AFTER the edit no longer chains");

        List<String> deleted = new ArrayList<>(original);
        deleted.remove(2);
        Files.write(file, deleted);
        assertFalse(sink.verify().valid());

        List<String> swapped = new ArrayList<>(original);
        Collections.swap(swapped, 1, 2);
        Files.write(file, swapped);
        assertFalse(sink.verify().valid());
    }

    @Test
    void truncatingTheTailIsCaughtByTheKeyedHead() throws IOException {
        fill(5);
        Files.write(file, lines().subList(0, 3));
        ChainedAuditFileSink.VerifyResult result = sink.verify();
        assertFalse(result.valid());
        assertTrue(result.reason().contains("head"));
    }

    @Test
    void aSelfConsistentRewriteWithAnotherKeyIsCaught() throws IOException {
        fill(3);
        Files.delete(file);
        Files.delete(dir.resolve("audit.log.head"));
        ChainedAuditFileSink forger = new ChainedAuditFileSink(file, "attacker-key-attacker-key-attack!".getBytes(StandardCharsets.UTF_8),
                new MutableClock(Instant.parse("2026-10-07T09:00:00Z")));
        for (int i = 0; i < 3; i++) forger.append("FORGED");
        assertTrue(forger.verify().valid(), "internally consistent");
        assertFalse(sink.verify().valid(), "but not under the real key");
    }

    @Test
    void concurrentAppendsKeepTheChainIntact() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 30; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    sink.append("CONCURRENT " + n);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(30, sink.verify().lines());
        assertTrue(sink.verify().valid());
    }

    // ---- anchors: what the chain and the keyed head alone cannot see

    private ChainedAuditFileSink.Anchor anchorOfNewestLine() throws IOException {
        AuditFileSink.Head head = sink.head().orElseThrow();
        return new ChainedAuditFileSink.Anchor(head.seq(), head.lineHash());
    }

    @Test
    void aWholeFileRollbackToAnOlderLogAndHeadPassesTheChainButNotAnAnchor() throws IOException {
        fill(10);
        byte[] oldLog = Files.readAllBytes(file);
        byte[] oldHead = Files.readAllBytes(dir.resolve("audit.log.head"));
        fill(5);
        ChainedAuditFileSink.Anchor anchor = anchorOfNewestLine();                      // pinned in the database while line 15 was the newest
        Files.write(file, oldLog);                                                       // the attacker restores yesterday's log AND yesterday's head record
        Files.write(dir.resolve("audit.log.head"), oldHead);
        assertTrue(sink.verify().valid(), "the chain and the keyed head are internally consistent: this is the gap");
        ChainedAuditFileSink.VerifyResult result = sink.verify(List.of(anchor));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("rolled back"), result.reason());
        assertTrue(result.reason().contains("anchored line 15"), result.reason());
    }

    @Test
    void aRewriteBySomeoneWhoHoldsTheKeyIsCaughtByAnAnchor() throws IOException {
        fill(8);
        ChainedAuditFileSink.Anchor anchor = anchorOfNewestLine();
        Files.delete(file);
        Files.delete(dir.resolve("audit.log.head"));
        for (int i = 1; i <= 8; i++) sink.append("A DIFFERENT HISTORY " + i);          // a perfectly valid chain, signed with the real key, with other content
        assertTrue(sink.verify().valid());
        ChainedAuditFileSink.VerifyResult result = sink.verify(List.of(anchor));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("anchored in the database"), result.reason());
    }

    @Test
    void anAnchorThatMatchesIsAcceptedAndGrowthAfterItIsFine() throws IOException {
        fill(6);
        ChainedAuditFileSink.Anchor anchor = anchorOfNewestLine();
        fill(4);
        ChainedAuditFileSink.VerifyResult result = sink.verify(List.of(anchor));
        assertTrue(result.valid(), result.reason());
        assertEquals(10, result.lines());
    }

    @Test
    void anEmptiedFileIsCaughtWhenAnchorsExistAndHeadIsEmptyForAnEmptyFile() throws IOException {
        fill(3);
        ChainedAuditFileSink.Anchor anchor = anchorOfNewestLine();
        Files.delete(file);
        Files.delete(dir.resolve("audit.log.head"));
        assertFalse(sink.verify(List.of(anchor)).valid());
        assertTrue(sink.verify(List.of()).valid());
        assertTrue(new ChainedAuditFileSink(dir.resolve("none.log"), KEY, new MutableClock(Instant.parse("2026-10-07T09:00:00Z"))).head().isEmpty());
    }

    // ------------------------------------------------------------------------------------------------------ encryption at rest

    private ChainedAuditFileSink sealedSink() {
        return new ChainedAuditFileSink(file, KEY, new MutableClock(Instant.parse("2026-10-07T09:00:00Z")), com.kaarigarsetu.support.TestCiphers.audit());
    }

    @Test
    void everyLineIsEncryptedAndTheFileStillVerifiesWithTheKeyAndNeverShowsTheEventText() throws IOException {
        ChainedAuditFileSink sealed = sealedSink();
        for (int i = 1; i <= 6; i++) sealed.append("MODERATOR_DECISION actor=moderator:alice details=flag=" + i + " SECRET-DETAIL");
        for (String line : lines()) {
            assertTrue(line.startsWith("enc:v1:"), line);
            assertFalse(line.contains("SECRET-DETAIL") || line.contains("MODERATOR_DECISION") || line.contains("2026-10-07") || line.contains("\"prev\""), "nothing readable: " + line);
        }
        assertTrue(sealed.verify().valid());
        assertEquals(6, sealed.verify().lines());
        assertEquals(List.of(0L, 6L), List.of(sealed.lineCounts().plain(), sealed.lineCounts().sealed()));
        assertTrue(sealed.plainLines().get(2).contains("flag=3 SECRET-DETAIL"));
        assertTrue(sealed.plainLines().get(2).contains("\"seq\":3"));
    }

    @Test
    void theHeadHashAndAnchorsAreOverThePlaintextSoTheyAreTheSameWhetherOrNotTheFileIsEncrypted() throws IOException {
        sink.append("ONE");
        sink.append("TWO");
        AuditFileSink.Head plainHead = sink.head().orElseThrow();
        Path copy = dir.resolve("copy.log");
        Files.copy(file, copy);
        Files.copy(dir.resolve("audit.log.head"), dir.resolve("copy.log.head"));
        ChainedAuditFileSink sealedCopy = new ChainedAuditFileSink(copy, KEY, new MutableClock(Instant.parse("2026-10-07T09:00:00Z")), com.kaarigarsetu.support.TestCiphers.audit());
        assertEquals(2, sealedCopy.sealLegacyLines());
        assertEquals(plainHead, sealedCopy.head().orElseThrow(), "the same line hash: an anchor recorded before encryption still matches");
        assertTrue(sealedCopy.verify(List.of(new ChainedAuditFileSink.Anchor(plainHead.seq(), plainHead.lineHash()))).valid());
    }

    @Test
    void aFileWithPlainLinesAndThenEncryptedLinesVerifiesAndTheLegacyLinesCanBeEncryptedInPlace() throws IOException {
        fill(4);                                                    // written before encryption was switched on
        ChainedAuditFileSink sealed = sealedSink();
        sealed.append("FIVE");
        sealed.append("SIX");
        assertEquals(List.of(4L, 2L), List.of(sealed.lineCounts().plain(), sealed.lineCounts().sealed()));
        assertTrue(sealed.verify().valid(), "a mixed file verifies");
        AuditFileSink.Head before = sealed.head().orElseThrow();
        assertEquals(4, sealed.sealLegacyLines());
        assertEquals(0, sealed.lineCounts().plain());
        assertTrue(sealed.verify().valid(), "and still verifies once every line is encrypted");
        assertEquals(before, sealed.head().orElseThrow());
        assertEquals(0, sealed.sealLegacyLines(), "nothing left to do");
        assertEquals("EVENT 3", com.kaarigarsetu.util.Json.gson().fromJson(sealed.plainLines().get(2), com.google.gson.JsonObject.class).get("event").getAsString());
        assertFalse(Files.exists(dir.resolve("audit.log.sealing")), "no temporary file is left behind");
    }

    @Test
    void withoutTheDataKeyAnEncryptedFileCannotBeReadOrChecked() throws IOException {
        sealedSink().append("SECRET EVENT");
        ChainedAuditFileSink noKey = new ChainedAuditFileSink(file, KEY, new MutableClock(Instant.parse("2026-10-07T09:00:00Z")));
        ChainedAuditFileSink.VerifyResult result = noKey.verify();
        assertFalse(result.valid());
        assertTrue(result.reason().contains("cannot be decrypted"), result.reason());
        assertThrows(SealedDataException.class, noKey::plainLines);
        assertThrows(SealedDataException.class, () -> noKey.append("MORE"), "and it will not write after a line it cannot read");
        ChainedAuditFileSink wrongKey = new ChainedAuditFileSink(file, KEY, new MutableClock(Instant.parse("2026-10-07T09:00:00Z")),
                com.kaarigarsetu.security.DataCipher.of(com.kaarigarsetu.security.DataKeys.single(new byte[32]), com.kaarigarsetu.security.DataCipher.AUDIT, false));
        assertFalse(wrongKey.verify().valid());
    }

    @Test
    void reorderingTruncatingOrEditingEncryptedLinesIsStillDetected() throws IOException {
        ChainedAuditFileSink sealed = sealedSink();
        for (int i = 1; i <= 5; i++) sealed.append("EVENT " + i);
        List<String> original = lines();
        List<String> swapped = new ArrayList<>(original);
        Collections.swap(swapped, 1, 3);
        Files.write(file, swapped);
        assertFalse(sealed.verify().valid(), "a line moved to another position does not open there");
        Files.write(file, original.subList(0, 4));
        assertFalse(sealed.verify().valid(), "dropping the last line: the head record no longer matches");
        List<String> edited = new ArrayList<>(original);
        String line = edited.get(2);
        edited.set(2, line.substring(0, line.length() - 3) + (line.endsWith("AAA") ? "BBB" : "AAA"));
        Files.write(file, edited);
        assertFalse(sealed.verify().valid(), "an altered ciphertext fails its authentication");
        Files.write(file, original);
        assertTrue(sealed.verify().valid(), "and the untouched file is fine");
    }

    @Test
    void anAnchorKeptInTheDatabaseStillCatchesARolledBackEncryptedFile() throws IOException {
        ChainedAuditFileSink sealed = sealedSink();
        for (int i = 1; i <= 5; i++) sealed.append("EVENT " + i);
        AuditFileSink.Head head = sealed.head().orElseThrow();
        Path headFile = dir.resolve("audit.log.head");
        String headAtFive = Files.readString(headFile);
        List<String> older = lines().subList(0, 3);
        sealed.append("EVENT 6");
        Files.write(file, older);
        Files.writeString(headFile, headAtFive);
        assertFalse(sealed.verify(List.of(new ChainedAuditFileSink.Anchor(head.seq(), head.lineHash()))).valid());
    }
}
