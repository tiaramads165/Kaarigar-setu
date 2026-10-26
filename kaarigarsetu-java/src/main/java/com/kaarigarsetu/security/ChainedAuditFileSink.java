package com.kaarigarsetu.security;

import com.google.gson.JsonObject;
import com.kaarigarsetu.util.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tamper-evident audit file (java.nio.file). Every line is a JSON object holding the SHA-256 of the previous line,
 * so editing, deleting or reordering any line breaks the chain. A keyed "head" file beside it stores an HMAC over
 * the newest sequence number and line hash, which also exposes truncation of the tail or a rewrite by someone
 * who lacks the server key.
 *
 * <p>With a {@link DataCipher} every line is ALSO encrypted at rest: what is written is {@code enc:v1:...} holding the whole JSON line (so even the time and
 * the event type are hidden from someone who copies the file). The chain, the head record and the anchors in the database are all computed over the
 * PLAINTEXT line, so they mean exactly what they did before and a plain line can be encrypted in place without changing any hash
 * ({@link #sealLegacyLines()}). The price is that checking the file now needs the data key as well as the head key: an attacker who steals the file
 * can neither read it nor check it. A file may hold plain and sealed lines together; both verify.
 */
public final class ChainedAuditFileSink implements AuditFileSink {
    public record VerifyResult(boolean valid, long lines, long firstBadLine, String reason) {
        static VerifyResult ok(long lines) { return new VerifyResult(true, lines, 0, "ok"); }
        static VerifyResult bad(long lines, long line, String why) { return new VerifyResult(false, lines, line, why); }
    }

    private static final String GENESIS = "0".repeat(64);

    private final Path file;
    private final Path headFile;
    private final byte[] key;
    private static final String PURPOSE = "audit.file";

    private final Clock clock;
    private final DataCipher cipher;
    private final ReentrantLock lock = new ReentrantLock();

    /** A sink that writes plain lines (tests, and tools that only read). The application always passes a cipher. */
    public ChainedAuditFileSink(Path file, byte[] hmacKey, Clock clock) {
        this(file, hmacKey, clock, null);
    }

    public ChainedAuditFileSink(Path file, byte[] hmacKey, Clock clock, DataCipher cipher) {
        this.file = file.toAbsolutePath().normalize();
        this.headFile = this.file.resolveSibling(this.file.getFileName() + ".head");
        this.key = hmacKey.clone();
        this.clock = clock;
        this.cipher = cipher;
    }

    /** What is written for the plain JSON line number {@code seq}: the line itself, or its sealed form. */
    private String stored(String plainLine, long seq) {
        return cipher == null ? plainLine : cipher.seal(PURPOSE, seq, plainLine);
    }

    /** The plain JSON of stored line number {@code seq}. A sealed line needs the data key. */
    private String plain(String storedLine, long seq) {
        if (!DataCipher.isSealed(storedLine)) return storedLine;
        if (cipher == null) throw new SealedDataException("The audit file is encrypted: the data key (DATA_ENC_KEYS) is needed to read or check it");
        return cipher.open(PURPOSE, seq, storedLine);
    }

    /** Every line of the file as plain JSON, oldest first (for the operator's reading tool; needs the data key if any line is sealed). */
    public List<String> plainLines() throws IOException {
        lock.lock();
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            List<String> out = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) out.add(plain(lines.get(i), i + 1L));
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** How many lines are stored encrypted and how many are still plain (the operator's check; needs no key). */
    public record LineCounts(long sealed, long plain) {
    }

    public LineCounts lineCounts() throws IOException {
        lock.lock();
        try {
            long sealed = 0;
            long plain = 0;
            for (String line : Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.<String>of()) {
                if (DataCipher.isSealed(line)) sealed++; else plain++;
            }
            return new LineCounts(sealed, plain);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encrypts, in place, every line that is still plain. No hash changes (they are over the plaintext), so the chain, the head record and the database anchors
     * all still verify; the result is checked before the old file is replaced. The application must be STOPPED while this runs: it appends to the file, and
     * an append during the swap would be lost.
     *
     * @return how many lines were encrypted
     */
    public int sealLegacyLines() throws IOException {
        if (cipher == null) throw new IllegalStateException("no data key: nothing to encrypt with");
        lock.lock();
        try {
            if (!Files.exists(file)) return 0;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> rewritten = new ArrayList<>(lines.size());
            int converted = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (DataCipher.isSealed(line)) {
                    rewritten.add(line);
                } else {
                    rewritten.add(cipher.seal(PURPOSE, i + 1L, line));
                    converted++;
                }
            }
            if (converted == 0) return 0;
            Path temporary = file.resolveSibling(file.getFileName() + ".sealing");
            Files.write(temporary, (String.join("\n", rewritten) + "\n").getBytes(StandardCharsets.UTF_8));
            // read it back as the application would: the same plain lines, in the same order, or the old file stays
            List<String> check = Files.readAllLines(temporary, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!plain(check.get(i), i + 1L).equals(plain(lines.get(i), i + 1L))) {
                    Files.deleteIfExists(temporary);
                    throw new IllegalStateException("the encrypted copy of line " + (i + 1) + " does not read back the same; the audit file was left as it was");
                }
            }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return converted;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void append(String event) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(java.util.Objects.requireNonNull(file.getParent(), "audit file needs a parent directory"));
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 FileLock ignored = channel.lock()) {
                List<String> existing = Files.readAllLines(file, StandardCharsets.UTF_8);
                long seq = existing.size() + 1;
                JsonObject line = new JsonObject();
                line.addProperty("seq", seq);
                line.addProperty("ts", clock.instant().toString());
                line.addProperty("event", event);
                line.addProperty("prev", existing.isEmpty() ? GENESIS : sha256(plain(existing.get(existing.size() - 1), existing.size())));
                String text = Json.toJson(line);
                channel.write(ByteBuffer.wrap((stored(text, seq) + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
                Files.writeString(headFile, headMac(seq, sha256(text)), StandardCharsets.UTF_8);
            }
        } finally {
            lock.unlock();
        }
    }

    /** A line number and the hash recorded for it somewhere the file's custodian cannot rewrite (the database). */
    public record Anchor(long seq, String lineHash) {
    }

    @Override
    public java.util.Optional<AuditFileSink.Head> head() throws IOException {
        lock.lock();
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            return lines.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(new AuditFileSink.Head(lines.size(), sha256(plain(lines.get(lines.size() - 1), lines.size()))));
        } finally {
            lock.unlock();
        }
    }

    public VerifyResult verify() throws IOException {
        return verify(List.of());
    }

    /**
     * Checks the chain, the keyed head record AND the anchors. The chain and head catch edits, deletions, reordering and truncation by someone without the
     * key, but a copy of an OLDER log together with its OLDER head record is perfectly consistent, so a rollback passes. An anchor is the hash of a line
     * written to the database (a separate, append-only, hash-chained store) while that line was the newest: a file that is shorter than an anchor, or whose
     * line differs from it, has been rolled back or rewritten, even by someone who holds the key.
     */
    public VerifyResult verify(List<Anchor> anchors) throws IOException {
        lock.lock();
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            List<String> hashes = new ArrayList<>(lines.size());
            String prev = GENESIS;
            long n = 0;
            for (String storedLine : lines) {
                n++;
                String line;
                try {
                    line = plain(storedLine, n);
                } catch (SealedDataException unreadable) {
                    return VerifyResult.bad(lines.size(), n, "line " + n + " cannot be decrypted (wrong or missing data key, or the line was altered)");
                }
                JsonObject o;
                try {
                    o = Json.gson().fromJson(line, JsonObject.class);
                } catch (RuntimeException exception) {
                    return VerifyResult.bad(lines.size(), n, "line is not valid JSON");
                }
                if (o == null || !o.has("seq") || !o.has("prev")) return VerifyResult.bad(lines.size(), n, "missing fields");
                if (o.get("seq").getAsLong() != n) return VerifyResult.bad(lines.size(), n, "sequence gap or reorder");
                if (!o.get("prev").getAsString().equals(prev)) return VerifyResult.bad(lines.size(), n, "hash chain broken");
                prev = sha256(line);
                hashes.add(prev);
            }
            if (lines.isEmpty()) {
                if (Files.exists(headFile)) return VerifyResult.bad(0, 0, "log is empty but a head record exists");
                return anchors.isEmpty() ? VerifyResult.ok(0) : VerifyResult.bad(0, 0, "the database anchored line " + newest(anchors).seq() + " but the file is empty");
            }
            if (!Files.exists(headFile)) return VerifyResult.bad(lines.size(), lines.size(), "head record missing");
            String expected = headMac(lines.size(), prev);
            String actual = Files.readString(headFile, StandardCharsets.UTF_8).trim();
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
                return VerifyResult.bad(lines.size(), lines.size(), "head mismatch: lines truncated or log rewritten");
            }
            for (Anchor anchor : anchors.stream().sorted(java.util.Comparator.comparingLong(Anchor::seq).reversed()).toList()) {
                if (anchor.seq() < 1) continue;
                if (anchor.seq() > lines.size()) {
                    return VerifyResult.bad(lines.size(), lines.size(), "file has " + lines.size() + " lines but the database anchored line " + anchor.seq() + ": rolled back or truncated");
                }
                if (!MessageDigest.isEqual(hashes.get((int) anchor.seq() - 1).getBytes(StandardCharsets.US_ASCII), anchor.lineHash().getBytes(StandardCharsets.US_ASCII))) {
                    return VerifyResult.bad(lines.size(), anchor.seq(), "line " + anchor.seq() + " differs from the copy anchored in the database: file rewritten");
                }
            }
            return VerifyResult.ok(lines.size());
        } finally {
            lock.unlock();
        }
    }

    private static Anchor newest(List<Anchor> anchors) {
        return anchors.stream().max(java.util.Comparator.comparingLong(Anchor::seq)).orElseThrow();
    }

    private String headMac(long seq, String lineHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("audit-head:" + seq + ":" + lineHash).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
