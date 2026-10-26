package com.kaarigarsetu.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "A compromised web application cannot mint itself a moderator" is a design promise, so it is checked: nothing in the application calls the methods that
 * create or change moderator accounts (only the operator's tool does), and the database grants the application role no write access to that table.
 */
class ModeratorWriteAccessTest {
    private static final List<String> OPERATOR_ONLY = List.of("admin/ModeratorAdminCli.java", "dao/ModeratorDao.java");

    @Test
    void onlyTheOperatorToolCallsTheMethodsThatWriteModeratorAccounts() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src", "main", "java", "com", "kaarigarsetu");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (OPERATOR_ONLY.contains(relative)) continue;
                String text = Files.readString(file);
                for (String call : List.of("moderatorDao.insert(", "moderatorDao.setActive(", "moderatorDao.setPassword(", "moderators.insert(", "moderators.setActive(", "moderators.setPassword(")) {
                    if (text.contains(call)) offenders.add(relative + " calls " + call);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void theDatabaseRoleOfTheApplicationCannotInsertOrUpdateModerators() throws IOException {
        String roles = Files.readString(Path.of("..", "database", "setup_roles.sql"));
        assertTrue(roles.contains("REVOKE INSERT, UPDATE ON moderators FROM kaarigar_app"), "setup_roles.sql must take write access to moderators away from the application role");
        assertTrue(roles.indexOf("GRANT SELECT, INSERT, UPDATE ON ALL TABLES") < roles.indexOf("REVOKE INSERT, UPDATE ON moderators"), "the revoke must come after the blanket grant");
    }

    @Test
    void theFourEyesRuleIsAlsoACheckConstraintOfTheTable() throws IOException {
        String migration = Files.readString(Path.of("..", "database", "migrations", "008_named_moderators_and_two_person_approval.sql"));
        assertTrue(migration.contains("CONSTRAINT chk_four_eyes CHECK (status <> 'EXECUTED' OR (decided_by IS NOT NULL AND decided_by <> proposed_by))"));
    }
}
