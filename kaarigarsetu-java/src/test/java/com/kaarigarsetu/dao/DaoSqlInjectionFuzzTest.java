package com.kaarigarsetu.dao;

import com.kaarigarsetu.support.TestCiphers;
import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL-injection fuzzing against EVERY DAO method (blueprint Phase 8, Domain 3). Reflection finds each public method of
 * each *Dao class whose parameters are plain values; every String parameter is filled, in turn, with each attack payload
 * while the other parameters get harmless values (ids that match no row). A method passes when it either works or fails
 * with a data-level error (constraint, type), never with a SQL syntax/undefined-object error (SQLSTATE class 42), which
 * is what concatenated SQL would produce; afterwards every table must still exist and every pre-existing row must be
 * byte-for-byte unchanged. Methods that take a model record are not reflected over; they are covered by the explicit tests.
 */
class DaoSqlInjectionFuzzTest {
    private static final String URL = System.getenv("TEST_DB_URL");
    private static final long NO_SUCH_ID = 999_999L;
    private static final List<String> PAYLOADS = List.of(
            "' OR '1'='1", "'; DROP TABLE workers; --", "\" OR \"\"=\"\"", "1; SELECT pg_sleep(10) --", "' UNION SELECT NULL, NULL, NULL --",
            "x'); DELETE FROM audit_log; --", "%' OR 1=1 --", "admin'--", "\\'; TRUNCATE credentials; --", "$$; DROP SCHEMA public CASCADE; $$",
            "Robert'); DROP TABLE students;--", "\u0000", "a".repeat(5_000));

    private final ConnectionProvider provider = () -> DriverManager.getConnection(URL,
            System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && URL.endsWith("_test"), "TEST_DB_URL must name a *_test database");
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS public CASCADE");
            s.execute("CREATE SCHEMA public");
            Path dir = Path.of("..", "database");
            s.execute(Files.readString(dir.resolve("schema.sql")));
            try (Stream<Path> migrations = Files.list(dir.resolve("migrations"))) {
                for (Path m : migrations.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) s.execute(Files.readString(m));
            }
        }
    }

    private void seed() {
        WorkerDao workers = new WorkerDao(provider, TestCiphers.data());
        EmployerDao employers = new EmployerDao(provider, TestCiphers.data());
        Worker worker = workers.insertProtected(new Worker(null, "Canary Worker", "h1:canary-w", "electrician", "hi", BigDecimal.TEN, BigDecimal.ONE, null), new byte[]{1}, "v1");
        Employer employer = employers.insertProtected(new Employer(null, "Canary Employer", "h1:canary-e", false, null), new byte[]{2}, "v1");
        AttestationDao attestations = new AttestationDao(provider, TestCiphers.data());
        AttestationDetail attestation = attestations.insertWorkflow(worker.workerId(), employer.employerId(), "canary-otp", "wiring", 1, true, LocalDate.now(), "ip", "dev");
        new CredentialDao(provider, TestCiphers.data()).insertIssued(worker.workerId(), "{\"canary\":true}", "sig", LocalDateTime.now().plusDays(1), "kid", UUID.randomUUID());
        new TrustEdgeDao(provider).insertAttestationEdge(employer.employerId(), worker.workerId(), BigDecimal.ONE, attestation.attestationId());
        new FraudFlagDao(provider, TestCiphers.data()).insertWithFeatures(attestation.attestationId(), new BigDecimal("0.9"), "{}");
        new OtpChallengeDao(provider).save("canary-key", "salt$hash", Instant.now().plusSeconds(600), "ip");
        new AuditLogDao(provider, TestCiphers.audit()).insert(new com.kaarigarsetu.model.AuditLog(null, "CANARY", "actor", "details", null));
    }

    /** Every row of every table, as text. */
    private Set<String> snapshot() throws SQLException {
        Set<String> rows = new HashSet<>();
        try (Connection c = provider.getConnection(); var s = c.createStatement();
             ResultSet tables = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            List<String> names = new ArrayList<>();
            while (tables.next()) names.add(tables.getString(1));
            for (String table : names) {
                try (var statement = c.createStatement(); ResultSet r = statement.executeQuery("SELECT t::text FROM " + table + " t")) {
                    while (r.next()) rows.add(table + ":" + r.getString(1));
                }
            }
        }
        return rows;
    }

    private static Set<String> tableNames(Connection c) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (var s = c.createStatement(); ResultSet r = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (r.next()) names.add(r.getString(1));
        }
        return names;
    }

    private static Object harmless(Class<?> type) {
        if (type == String.class) return "harmless";
        if (type == long.class || type == Long.class) return NO_SUCH_ID;
        if (type == int.class || type == Integer.class) return 1;
        if (type == double.class || type == Double.class) return 0.5;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (type == UUID.class) return UUID.randomUUID();
        if (type == LocalDate.class) return LocalDate.now();
        if (type == LocalDateTime.class) return LocalDateTime.now();
        if (type == Instant.class) return Instant.now();
        if (type == byte[].class) return new byte[]{1};
        return null;
    }

    private static boolean supported(Class<?> type) {
        return harmless(type) != null;
    }

    @Test
    void noDaoMethodCanBeInjectedAndNoExistingRowChanges() throws Exception {
        seed();
        Set<String> tablesBefore;
        try (Connection c = provider.getConnection()) { tablesBefore = tableNames(c); }
        Set<String> rowsBefore = snapshot();

        List<Object> daos = List.of(new WorkerDao(provider, TestCiphers.data()), new EmployerDao(provider, TestCiphers.data()), new AssessmentDao(provider, TestCiphers.data()), new AttestationDao(provider, TestCiphers.data()),
                new TrustEdgeDao(provider), new CredentialDao(provider, TestCiphers.data()), new FraudFlagDao(provider, TestCiphers.data()), new AuditLogDao(provider, TestCiphers.audit()), new OtpChallengeDao(provider));
        int methodsWithStrings = 0, calls = 0, syntaxErrors = 0;
        List<String> skipped = new ArrayList<>(), problems = new ArrayList<>();

        for (Object dao : daos) {
            for (Method method : dao.getClass().getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) continue;
                Class<?>[] types = method.getParameterTypes();
                boolean hasString = false;
                boolean allSupported = true;
                for (Class<?> type : types) {
                    hasString |= type == String.class;
                    allSupported &= supported(type);
                }
                String name = dao.getClass().getSimpleName() + "." + method.getName();
                if (!hasString) continue;
                if (!allSupported) { skipped.add(name); continue; }
                methodsWithStrings++;
                for (int position = 0; position < types.length; position++) {
                    if (types[position] != String.class) continue;
                    for (String payload : PAYLOADS) {
                        Object[] args = new Object[types.length];
                        for (int i = 0; i < types.length; i++) args[i] = i == position ? payload : harmless(types[i]);
                        calls++;
                        try {
                            method.invoke(dao, args);
                        } catch (InvocationTargetException wrapped) {
                            Throwable cause = wrapped.getCause();
                            if (cause instanceof DataAccessException data && data.getCause() instanceof SQLException sql) {
                                String state = sql.getSQLState() == null ? "" : sql.getSQLState();
                                if (state.startsWith("42") || state.startsWith("0A")) { syntaxErrors++; problems.add(name + " arg" + position + " -> " + state + " " + sql.getMessage()); }
                            } else if (!(cause instanceof RuntimeException)) {
                                problems.add(name + " threw " + cause);
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("SQL-injection fuzz: %d DAO methods with String parameters, %d calls, %d payloads each; skipped (record params, covered by explicit tests): %s%n",
                methodsWithStrings, calls, PAYLOADS.size(), skipped);

        assertTrue(methodsWithStrings >= 20, "reflection found too few methods to be a meaningful fuzz: " + methodsWithStrings);
        assertEquals(List.of(), problems, "SQL syntax errors mean a payload reached the SQL text");
        assertEquals(0, syntaxErrors);
        try (Connection c = provider.getConnection()) { assertEquals(tablesBefore, tableNames(c), "no table was dropped or created"); }
        Set<String> rowsAfter = snapshot();
        Set<String> lost = new TreeSet<>(rowsBefore);
        lost.removeAll(rowsAfter);
        assertEquals(Set.of(), lost, "an existing row was altered or deleted");
    }

    /** Proves the detector is not blind: the same payloads against deliberately concatenated SQL DO surface as syntax errors. */
    @Test
    void theDetectorFlagsConcatenatedSqlSoAPassingFuzzMeansSomething() throws Exception {
        int detected = 0;
        try (Connection c = provider.getConnection()) {
            for (String payload : PAYLOADS) {
                try (var statement = c.createStatement()) {
                    statement.executeQuery("SELECT * FROM workers WHERE phone = '" + payload + "'");
                } catch (SQLException e) {
                    if (e.getSQLState() != null && e.getSQLState().startsWith("42")) detected++;
                }
            }
        }
        assertTrue(detected >= 3, "string-built SQL should break on several of the payloads, detected=" + detected);
    }
}
