package com.kaarigarsetu.moderator;

import com.kaarigarsetu.support.TestCiphers;
import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.ModeratorActionDao;
import com.kaarigarsetu.dao.ModeratorDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.trust.TrustScoreService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Named moderators and two-person approval, against a real PostgreSQL: the rules are in the SQL and the constraints, not only in the Java. */
class ModeratorApprovalIntegrationTest {
    private static final String URL = System.getenv("TEST_DB_URL");

    /** A thread-bound transaction over plain JDBC, so the approval's "mark executed AND do it" can be shown to be atomic. */
    private static final class Tx implements ConnectionProvider, Transactions {
        private final ThreadLocal<Connection> bound = new ThreadLocal<>();

        private static Connection open() throws SQLException {
            return DriverManager.getConnection(URL, System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection current = bound.get();
            if (current == null) return open();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> method.getName().equals("close") ? null : unwrap(method, current, args));
        }

        private static Object unwrap(java.lang.reflect.Method method, Connection target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                bound.set(connection);
                try {
                    T result = work.get();
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    bound.remove();
                }
            } catch (SQLException exception) {
                throw new DataAccessException("transaction", exception);
            }
        }
    }

    private final Tx tx = new Tx();
    private ModeratorDao moderators;
    private ModeratorActionDao actions;
    private EmployerDao employers;
    private Moderator alice;
    private Moderator bob;
    private Moderator carol;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && URL.endsWith("_test"), "TEST_DB_URL must name a *_test database");
        try (Connection c = Tx.open(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS public CASCADE");
            s.execute("CREATE SCHEMA public");
            Path dir = Path.of("..", "database");
            s.execute(Files.readString(dir.resolve("schema.sql")));
            try (Stream<Path> migrations = Files.list(dir.resolve("migrations"))) {
                for (Path m : migrations.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) s.execute(Files.readString(m));
            }
        }
        moderators = new ModeratorDao(tx);
        actions = new ModeratorActionDao(tx);
        employers = new EmployerDao(tx, TestCiphers.data());
        alice = moderators.insert("alice", "Alice Rao", "hash", new byte[]{1}, "operator:test");
        bob = moderators.insert("bob", "Bob Iyer", "hash", new byte[]{2}, "operator:test");
        carol = moderators.insert("carol", "Carol Das", "hash", new byte[]{3}, "operator:test");
    }

    private Employer employer(String key) {
        return employers.insert(new Employer(null, "Employer " + key, key, false, null));
    }

    private ModeratorService service() {
        return new ModeratorService(new FraudFlagDao(tx, TestCiphers.data()), new AttestationDao(tx, TestCiphers.data()), new TrustEdgeDao(tx), employers, mock(TrustScoreService.class), mock(CredentialService.class),
                mock(AuditLogger.class), tx, actions, AppSettings.of(Map.of()));
    }

    private String actionStatus(long id) throws Exception {
        try (Connection c = Tx.open(); var s = c.prepareStatement("SELECT status FROM moderator_actions WHERE action_id = ?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void accountsAreUniqueLowerCaseNamesAndCanBeDisabledAndReenabled() {
        assertTrue(moderators.findByUsername("alice").orElseThrow().active());
        assertThrows(DataAccessException.class, () -> moderators.insert("alice", "Another Alice", "h", new byte[]{9}, "operator:test"), "usernames are unique");
        for (String bad : new String[]{"Alice2", "a", "has space", "9lives", "x'; DROP TABLE moderators;--"}) {
            assertThrows(DataAccessException.class, () -> moderators.insert(bad, "Bad", "h", new byte[]{9}, "operator:test"), bad);
        }
        assertEquals(3, moderators.countActive());
        assertTrue(moderators.setActive("bob", false));
        assertFalse(moderators.findById(bob.moderatorId()).orElseThrow().active());
        assertEquals(2, moderators.countActive());
        assertTrue(moderators.setActive("bob", true));
        assertTrue(moderators.findByUsername("bob").orElseThrow().active());
        assertFalse(moderators.setActive("nobody", false));
        assertTrue(moderators.setPassword("carol", "new-hash"));
        assertEquals("new-hash", moderators.findByUsername("carol").orElseThrow().passwordHash());
    }

    // ------------------------------------------------------------------ four eyes, in the SQL and in the constraint

    @Test
    void theProposerCannotApproveAndAnotherModeratorCanExactlyOnce() throws Exception {
        long id = actions.propose("VET_EMPLOYER", 5L, null, alice.moderatorId()).orElseThrow();
        assertFalse(actions.approve(id, alice.moderatorId(), 72), "the update itself refuses the proposer");
        assertEquals("PENDING", actionStatus(id));
        assertTrue(actions.approve(id, bob.moderatorId(), 72));
        assertEquals("EXECUTED", actionStatus(id));
        assertFalse(actions.approve(id, carol.moderatorId(), 72), "it is carried out once");
        var done = actions.findById(id).orElseThrow();
        assertEquals("bob", done.decidedByUsername());
        assertEquals("alice", done.proposedByUsername());
    }

    @Test
    void evenRawSqlCannotMakeTheProposerTheApprover() throws Exception {
        long id = actions.propose("VET_EMPLOYER", 5L, null, alice.moderatorId()).orElseThrow();
        try (Connection c = Tx.open(); var s = c.createStatement()) {
            SQLException refused = assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE moderator_actions SET status = 'EXECUTED', decided_by = proposed_by, decided_at = CURRENT_TIMESTAMP WHERE action_id = " + id));
            assertEquals("23514", refused.getSQLState(), "check_violation: the four-eyes constraint");
            SQLException noDecider = assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE moderator_actions SET status = 'EXECUTED', decided_at = CURRENT_TIMESTAMP WHERE action_id = " + id));
            assertEquals("23514", noDecider.getSQLState(), "an executed row must name who approved it");
        }
        assertEquals("PENDING", actionStatus(id));
    }

    @Test
    void oneOpenProposalPerTargetAndKindAndATargetCanBeProposedAgainAfterARejection() {
        long first = actions.propose("VET_EMPLOYER", 5L, null, alice.moderatorId()).orElseThrow();
        assertTrue(actions.propose("VET_EMPLOYER", 5L, null, bob.moderatorId()).isEmpty(), "a duplicate waits behind nothing: it is refused");
        assertTrue(actions.propose("VET_EMPLOYER", 6L, null, bob.moderatorId()).isPresent(), "another target is independent");
        assertTrue(actions.propose("DISPUTE_DECISION", 5L, "REMOVED", bob.moderatorId()).isPresent(), "and so is another kind");
        assertTrue(actions.reject(first, bob.moderatorId()));
        assertTrue(actions.propose("VET_EMPLOYER", 5L, null, bob.moderatorId()).isPresent());
    }

    @Test
    void aRulingNeedsAVerdictAndAVettingMustNotHaveOne() {
        assertThrows(DataAccessException.class, () -> actions.propose("DISPUTE_DECISION", 5L, null, alice.moderatorId()));
        assertThrows(DataAccessException.class, () -> actions.propose("VET_EMPLOYER", 5L, "UPHELD", alice.moderatorId()));
        assertThrows(DataAccessException.class, () -> actions.propose("DISPUTE_DECISION", 5L, "MAYBE", alice.moderatorId()));
        assertThrows(DataAccessException.class, () -> actions.propose("SOMETHING_ELSE", 5L, null, alice.moderatorId()));
    }

    @Test
    void onlyTheProposerWithdrawsAndOnlyAnotherModeratorRejects() throws Exception {
        long id = actions.propose("VET_EMPLOYER", 5L, null, alice.moderatorId()).orElseThrow();
        assertFalse(actions.reject(id, alice.moderatorId()), "you do not reject your own: you withdraw it");
        assertFalse(actions.withdraw(id, bob.moderatorId()), "nobody else withdraws it");
        assertTrue(actions.withdraw(id, alice.moderatorId()));
        assertEquals("WITHDRAWN", actionStatus(id));
        assertFalse(actions.approve(id, bob.moderatorId(), 72), "a closed proposal cannot be approved");
    }

    @Test
    void aProposalPastItsLifetimeCannotBeApprovedAndIsMarkedExpired() throws Exception {
        long id = actions.propose("VET_EMPLOYER", 5L, null, alice.moderatorId()).orElseThrow();
        try (Connection c = Tx.open(); var s = c.createStatement()) {
            s.execute("UPDATE moderator_actions SET proposed_at = CURRENT_TIMESTAMP - INTERVAL '100 hours' WHERE action_id = " + id);
        }
        assertTrue(actions.findPending(72).isEmpty(), "not offered for approval any more");
        assertFalse(actions.approve(id, bob.moderatorId(), 72));
        assertEquals(1, actions.expireOlderThan(72));
        assertEquals("EXPIRED", actionStatus(id));
        assertTrue(actions.propose("VET_EMPLOYER", 5L, null, bob.moderatorId()).isPresent(), "an expired proposal no longer blocks a new one");
    }

    @Test
    void twoModeratorsApprovingAtTheSameMomentCarryItOutOnce() throws Exception {
        long id = actions.propose("VET_EMPLOYER", 5L, null, alice.moderatorId()).orElseThrow();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Moderator approver : List.of(bob, carol)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return actions.approve(id, approver.moderatorId(), 72);
                }));
            }
            start.countDown();
            int won = 0;
            for (var result : results) if (result.get()) won++;
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the whole service against the real database

    @Test
    void vettingTakesEffectOnlyWhenADifferentModeratorApproves() {
        ModeratorService service = service();
        Employer e = employer("9100000001");
        var outcome = service.vetEmployer(e.employerId(), alice);
        assertFalse(outcome.executed());
        assertFalse(employers.findById(e.employerId()).orElseThrow().verified(), "proposing vets nobody");
        assertThrows(ForbiddenException.class, () -> service.approve(outcome.actionId(), alice));
        assertFalse(employers.findById(e.employerId()).orElseThrow().verified(), "the proposer approving their own proposal vets nobody");
        service.approve(outcome.actionId(), bob);
        assertTrue(employers.findById(e.employerId()).orElseThrow().verified());
        assertEquals("alice (approved by bob)", verifiedBy(e.employerId()), "the record names both people");
    }

    private String verifiedBy(long employerId) {
        try (Connection c = Tx.open(); var s = c.prepareStatement("SELECT verified_by FROM employers WHERE employer_id = ?")) {
            s.setLong(1, employerId);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void ifCarryingItOutFailsTheApprovalIsRolledBackAndTheProposalStaysPending() throws Exception {
        ModeratorService service = service();
        Employer e = employer("9100000002");
        var outcome = service.vetEmployer(e.employerId(), alice);
        employers.anonymise(e.employerId());                                  // the employer erased their account in the meantime
        assertThrows(NotFoundException.class, () -> service.approve(outcome.actionId(), bob));
        assertEquals("PENDING", actionStatus(outcome.actionId()), "it was not marked executed for something that never happened");
        service.reject(outcome.actionId(), bob);
        assertEquals("REJECTED", actionStatus(outcome.actionId()));
    }

    @Test
    void aDisabledModeratorsPendingProposalCanStillBeDecidedByTheOthers() throws Exception {
        ModeratorService service = service();
        Employer e = employer("9100000003");
        var outcome = service.vetEmployer(e.employerId(), alice);
        moderators.setActive("alice", false);
        service.approve(outcome.actionId(), bob);
        assertTrue(employers.findById(e.employerId()).orElseThrow().verified());
    }
}
