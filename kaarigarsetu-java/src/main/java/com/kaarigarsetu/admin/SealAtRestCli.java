package com.kaarigarsetu.admin;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.security.ChainedAuditFileSink;
import com.kaarigarsetu.security.DataCipher;
import com.kaarigarsetu.security.SealedDataException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The operator's tool for encryption at rest (see security/DataCipher and docs/SECURITY_RUNBOOK.md).
 *
 * <pre>
 *   check                  count, for every sealed column, the exported credential files and the audit file, what is encrypted, what is still plain,
 *                          what is sealed with an older key, and whether every encrypted value actually opens. Exit 1 if anything is plain or unreadable.
 *   seal [--app-stopped]   encrypt what is still plain and re-encrypt what an older key sealed, under the CURRENT key. Safe to run while the application
 *                          runs (a row that changed meanwhile is skipped and reported: run it again). Encrypting the audit FILE swaps the file, so it
 *                          happens only with --app-stopped.
 *   audit-read [N]         print the last N (default 50) audit file lines, decrypted.
 * </pre>
 *
 * Use it after migration 010 (existing values are still plain), and after rotating the key (put the new key FIRST in DATA_ENC_KEYS, run {@code seal},
 * {@code check}, and only then drop the old key). Two things can never be re-encrypted, by design: the audit TABLE and the audit FILE are append-only and
 * hash-chained, so an audit line sealed with an old key keeps that key: never drop a key that ever sealed an audit line.
 * Connection: ADMIN_DB_URL, or DB_HOST, DB_PORT and DB_NAME; ADMIN_DB_USER (default: the operating-system user) and ADMIN_DB_PASSWORD. Keys come from the environment.
 */
public final class SealAtRestCli {
    private static final String EXPORT_PURPOSE = "credential.export";

    private final ConnectionProvider provider;
    private final DataCipher data;
    private final DataCipher files;
    private final ChainedAuditFileSink auditFile;
    private final Path exportDirectory;
    private final PrintStream out;
    private final PrintStream err;

    SealAtRestCli(ConnectionProvider provider, DataCipher data, DataCipher files, ChainedAuditFileSink auditFile, Path exportDirectory, PrintStream out, PrintStream err) {
        this.provider = provider;
        this.data = data;
        this.files = files;
        this.auditFile = auditFile;
        this.exportDirectory = exportDirectory;
        this.out = out;
        this.err = err;
    }

    /** @return the process exit code: 0 fine, 1 something is plain, unreadable or was skipped, 2 usage */
    int run(String[] args) {
        if (args.length == 0) return usage();
        try {
            return switch (args[0]) {
                case "check" -> args.length == 1 ? check() : usage();
                case "seal" -> args.length == 1 || (args.length == 2 && args[1].equals("--app-stopped")) ? seal(args.length == 2) : usage();
                case "audit-read" -> args.length <= 2 ? auditRead(args.length == 2 ? Integer.parseInt(args[1]) : 50) : usage();
                default -> usage();
            };
        } catch (SQLException | IOException | SealedDataException | NumberFormatException failure) {
            err.println("failed: " + failure.getMessage());
            return 1;
        }
    }

    private int usage() {
        err.println("usage: SealAtRestCli check | seal [--app-stopped] | audit-read [lines]");
        return 2;
    }

    // ---------------------------------------------------------------------------------------------------------------- check

    private record Counts(long plain, long current, long other, long unreadable) {
    }

    private int check() throws SQLException, IOException {
        long problems = 0;
        long older = 0;
        out.println(String.format("%-36s %8s %10s %10s %10s", "column", "plain", "sealed", "older key", "unreadable"));
        try (Connection connection = provider.getConnection()) {
            for (SealedColumns.Column column : SealedColumns.ALL) {
                long[] tally = new long[4];
                scan(connection, column.select(), (id, value) -> classify(column.purpose(), id, value, tally));
                out.println(String.format("%-36s %8d %10d %10d %10d", column.purpose(), tally[0], tally[1], tally[2], tally[3]));
                problems += tally[0] + tally[3];
                older += tally[2];
            }
            reportAuditTable(connection);
        }
        Counts exports = checkExports();
        out.println(String.format("%-36s %8d %10d %10d %10d", "credential export files", exports.plain(), exports.current(), exports.other(), exports.unreadable()));
        problems += exports.plain() + exports.unreadable();
        older += exports.other();
        if (auditFile != null) {
            ChainedAuditFileSink.LineCounts lines = auditFile.lineCounts();
            long unreadable = 0;
            try {
                auditFile.plainLines();
            } catch (SealedDataException cannotOpen) {
                unreadable = lines.sealed();
            }
            out.println(String.format("%-36s %8d %10d %10s %10d", "audit file lines", lines.plain(), lines.sealed(), "-", unreadable));
            problems += lines.plain() + unreadable;
        }
        if (problems > 0) {
            err.println(problems + " value(s) are plain or cannot be opened. Plain values: run 'seal' (the audit file needs the application stopped: 'seal --app-stopped').");
            return 1;
        }
        if (older > 0) out.println(older + " value(s) are still sealed with an older key: run 'seal' before dropping that key from DATA_ENC_KEYS.");
        out.println("ok: everything that should be encrypted is, and opens.");
        return 0;
    }

    private void classify(String purpose, long id, String value, long[] tally) {
        if (!DataCipher.isSealed(value)) {
            tally[0]++;
            return;
        }
        try {
            data.open(purpose, id, value);
            tally[data.usesCurrentKey(value) ? 1 : 2]++;
        } catch (SealedDataException cannotOpen) {
            tally[3]++;
        }
    }

    private void reportAuditTable(Connection connection) throws SQLException {
        String sql = "SELECT count(*) FILTER (WHERE details LIKE 'enc:v1:%'), count(*) FILTER (WHERE details NOT LIKE 'enc:v1:%') FROM audit_log";
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            rows.next();
            out.println(String.format("%-36s %8s %10d %10s %10s", "audit_log.details (append-only)", rows.getLong(2) + " legacy", rows.getLong(1), "-", "-")
                    + (rows.getLong(2) > 0 ? "   (rows written before sealing stay plain: the table cannot be changed)" : ""));
        }
    }

    private Counts checkExports() throws IOException {
        long[] tally = new long[4];
        for (Path file : exportFiles()) {
            String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
            classifyFile(file, stored, tally);
        }
        return new Counts(tally[0], tally[1], tally[2], tally[3]);
    }

    private void classifyFile(Path file, String stored, long[] tally) {
        if (!DataCipher.isSealed(stored)) {
            tally[0]++;
            return;
        }
        try {
            files.open(EXPORT_PURPOSE, referenceOf(file), stored);
            tally[files.usesCurrentKey(stored) ? 1 : 2]++;
        } catch (SealedDataException cannotOpen) {
            tally[3]++;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------- seal

    private int seal(boolean appStopped) throws SQLException, IOException {
        long changed = 0;
        long skipped = 0;
        try (Connection connection = provider.getConnection()) {
            connection.setAutoCommit(false);
            for (SealedColumns.Column column : SealedColumns.ALL) {
                List<Object[]> changes = new ArrayList<>();
                scan(connection, column.select(), (id, value) -> {
                    if (!DataCipher.isSealed(value) || !data.usesCurrentKey(value)) {
                        changes.add(new Object[]{id, value, data.seal(column.purpose(), id, data.open(column.purpose(), id, value))});
                    }
                });
                int[] result = applyChanges(connection, column.update(), changes);
                connection.commit();
                long done = java.util.Arrays.stream(result).filter(n -> n == 1).count();
                changed += done;
                skipped += result.length - done;
                if (!changes.isEmpty()) out.println(String.format("%-36s encrypted %d%s", column.purpose(), done, result.length - done > 0 ? ", skipped " + (result.length - done) + " (changed meanwhile)" : ""));
            }
        }
        int exports = sealExports();
        if (exports > 0) out.println(String.format("%-36s encrypted %d", "credential export files", exports));
        if (auditFile != null) {
            if (appStopped) {
                int lines = auditFile.sealLegacyLines();
                if (lines > 0) out.println(String.format("%-36s encrypted %d (chain, head record and database anchors are unchanged)", "audit file lines", lines));
            } else if (auditFile.lineCounts().plain() > 0) {
                out.println("audit file: " + auditFile.lineCounts().plain() + " plain line(s) NOT encrypted: stop the application and run 'seal --app-stopped'.");
            }
        }
        out.println(changed == 0 && exports == 0 ? "nothing to do: everything is already encrypted under the current key." : "done.");
        if (skipped > 0) {
            err.println(skipped + " row(s) changed while this ran and were skipped: run 'seal' again.");
            return 1;
        }
        return 0;
    }

    private int sealExports() throws IOException {
        int count = 0;
        for (Path file : exportFiles()) {
            String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (DataCipher.isSealed(stored) && files.usesCurrentKey(stored)) continue;
            String reference = referenceOf(file);
            String sealed = files.seal(EXPORT_PURPOSE, reference, files.open(EXPORT_PURPOSE, reference, stored));
            Path temporary = file.resolveSibling(file.getFileName() + ".sealing");
            Files.writeString(temporary, sealed, StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            count++;
        }
        return count;
    }

    // ----------------------------------------------------------------------------------------------------------- audit-read

    private int auditRead(int last) throws IOException {
        if (auditFile == null) {
            err.println("no audit file configured");
            return 1;
        }
        List<String> lines = auditFile.plainLines();
        for (String line : lines.subList(Math.max(0, lines.size() - last), lines.size())) out.println(line);
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface Row {
        void accept(long id, String value);
    }

    /** Runs a fixed SELECT of (id, value) pairs from SealedColumns: the SQL is a literal from that registry, never built from input. */
    private static void scan(Connection connection, String sql, Row row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) row.accept(rows.getLong(1), rows.getString(2));
        }
    }

    private static int[] applyChanges(Connection connection, String sql, List<Object[]> changes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] change : changes) {
                statement.setString(1, (String) change[2]);
                statement.setLong(2, (Long) change[0]);
                statement.setString(3, (String) change[1]);
                statement.addBatch();
            }
            return statement.executeBatch();
        }
    }

    private List<Path> exportFiles() throws IOException {
        if (exportDirectory == null || !Files.isDirectory(exportDirectory)) return List.of();
        try (Stream<Path> listing = Files.list(exportDirectory)) {
            return listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String referenceOf(Path file) {
        String name = java.util.Objects.requireNonNull(file.getFileName(), "a file path has a name").toString();
        return name.substring(0, name.length() - ".json".length());
    }

    public static void main(String[] args) {
        AppSettings settings = AppSettings.fromEnvironment();
        String url = envOr("ADMIN_DB_URL", "jdbc:postgresql://" + envOr("DB_HOST", "localhost") + ":" + envOr("DB_PORT", "5432") + "/" + envOr("DB_NAME", "kaarigar_setu"));
        String user = envOr("ADMIN_DB_USER", System.getProperty("user.name"));
        String secret = envOr("ADMIN_DB_PASSWORD", "");
        ConnectionProvider provider = () -> DriverManager.getConnection(url, user, secret);
        DataCipher data = DataCipher.of(settings.dataKeys(), DataCipher.DATA, false);
        ChainedAuditFileSink auditFile = new ChainedAuditFileSink(settings.auditLogPath(), settings.auditHmacKey(), Clock.systemUTC(),
                DataCipher.of(settings.dataKeys(), DataCipher.AUDIT, false));
        SealAtRestCli cli = new SealAtRestCli(provider, data, data.forDomain(DataCipher.FILES), auditFile, Path.of(envOr("CREDENTIAL_EXPORT_DIR", "data/credentials")), System.out, System.err);
        System.exit(cli.run(args));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
