package com.kaarigarsetu.admin;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.dao.AuditLogDao;
import com.kaarigarsetu.dao.ModeratorDao;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.model.AuditLog;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.security.PasswordHasher;
import com.kaarigarsetu.security.PhoneProtector;

import java.io.PrintStream;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The operator's tool for moderator accounts: create, list, disable, enable, change password. It is deliberately NOT part of the web application and needs
 * database-OWNER rights: the application's own database role can only read the moderators table (setup_roles.sql), so a compromised web application cannot
 * mint a moderator, and no moderator can create another one.
 *
 * <pre>
 *   ADMIN_DB_USER=... ADMIN_DB_PASSWORD=... java -cp ... com.kaarigarsetu.admin.ModeratorAdminCli add alice "Alice Rao" 9876543210   (password on stdin)
 *   ... list | disable alice | enable alice | set-password alice
 * </pre>
 *
 * Connection: ADMIN_DB_URL, or built from DB_HOST, DB_PORT and DB_NAME; user ADMIN_DB_USER (default: the operating-system user); password ADMIN_DB_PASSWORD.
 * Phone encryption uses PHONE_ENC_KEY and PHONE_HMAC_KEY from the environment. Passwords are read from standard input, never from the command line.
 * Each change is written to the database audit trail with the operator's operating-system user name as the actor.
 */
public final class ModeratorAdminCli {
    static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9._-]{2,39}$");
    static final int MIN_PASSWORD = 12;

    private final ModeratorDao moderators;
    private final PhoneProtector phones;
    private final AuditLogDao audit;
    private final String operator;
    private final Supplier<char[]> password;
    private final PrintStream out;
    private final PrintStream err;

    ModeratorAdminCli(ModeratorDao moderators, PhoneProtector phones, AuditLogDao audit, String operator, Supplier<char[]> password, PrintStream out, PrintStream err) {
        this.moderators = moderators;
        this.phones = phones;
        this.audit = audit;
        this.operator = operator;
        this.password = password;
        this.out = out;
        this.err = err;
    }

    /** @return the process exit code: 0 done, 1 refused or failed, 2 usage */
    int run(String[] args) {
        if (args.length == 0) return usage();
        try {
            return switch (args[0]) {
                case "add" -> args.length == 4 ? add(args[1], args[2], args[3]) : usage();
                case "list" -> args.length == 1 ? list() : usage();
                case "disable" -> args.length == 2 ? setActive(args[1], false) : usage();
                case "enable" -> args.length == 2 ? setActive(args[1], true) : usage();
                case "set-password" -> args.length == 2 ? setPassword(args[1]) : usage();
                default -> usage();
            };
        } catch (DataAccessException | IllegalArgumentException | com.kaarigarsetu.exception.ValidationException failure) {
            err.println("refused: " + (failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage()));
            return 1;
        }
    }

    private int usage() {
        err.println("usage: ModeratorAdminCli add <username> \"<Display Name>\" <mobile number> | list | disable <username> | enable <username> | set-password <username>");
        err.println("       the password is read from standard input; usernames are 3-40 characters: a-z, 0-9, dot, dash, underscore, starting with a letter");
        return 2;
    }

    private int add(String username, String displayName, String phone) {
        if (!USERNAME.matcher(username).matches()) throw new IllegalArgumentException("username must match " + USERNAME.pattern());
        if (displayName.isBlank() || displayName.length() > 80) throw new IllegalArgumentException("the display name must be 1 to 80 characters");
        String normalized = PhoneProtector.normalize(phone);
        char[] chosen = acceptable(username);
        Moderator created = moderators.insert(username, displayName.strip(), PasswordHasher.hash(chosen), phones.encrypt(normalized), "operator:" + operator);
        record("MODERATOR_ACCOUNT_CREATED", "username=" + created.username());
        out.println("created moderator " + created.username() + " (id " + created.moderatorId() + "); their sign-in codes go to ******" + normalized.substring(normalized.length() - 4));
        warnIfFewerThanTwo();
        return 0;
    }

    private int list() {
        List<Moderator> all = moderators.findAll();
        if (all.isEmpty()) out.println("no moderators yet: create at least two (vetting and dispute rulings need a second person)");
        for (Moderator m : all) out.println(m.moderatorId() + "\t" + m.username() + "\t" + (m.active() ? "active" : "DISABLED") + "\t" + m.displayName() + "\tcreated " + m.createdAt() + " by " + m.createdBy());
        return 0;
    }

    private int setActive(String username, boolean active) {
        if (!moderators.setActive(username, active)) {
            err.println("no moderator called " + username);
            return 1;
        }
        record(active ? "MODERATOR_ACCOUNT_ENABLED" : "MODERATOR_ACCOUNT_DISABLED", "username=" + username);
        out.println((active ? "enabled " : "disabled ") + username + (active ? "" : ": any session they have open stops working on its next request"));
        warnIfFewerThanTwo();
        return 0;
    }

    private int setPassword(String username) {
        char[] chosen = acceptable(username);
        if (!moderators.setPassword(username, PasswordHasher.hash(chosen))) {
            err.println("no moderator called " + username);
            return 1;
        }
        record("MODERATOR_PASSWORD_CHANGED", "username=" + username);
        out.println("password changed for " + username);
        return 0;
    }

    private char[] acceptable(String username) {
        char[] chosen = password.get();
        if (chosen == null || chosen.length < MIN_PASSWORD) throw new IllegalArgumentException("the password must be at least " + MIN_PASSWORD + " characters");
        if (new String(chosen).equalsIgnoreCase(username)) throw new IllegalArgumentException("the password must not be the username");
        return chosen;
    }

    private void record(String event, String details) {
        audit.insert(new AuditLog(null, event, "operator:" + operator, details, LocalDateTime.now()));
    }

    private void warnIfFewerThanTwo() {
        int active = moderators.countActive();
        if (active < 2) err.println("warning: " + active + " active moderator(s). With two-person approval on, vetting and dispute rulings cannot be approved until there are at least two.");
    }

    public static void main(String[] args) {
        AppSettings settings = AppSettings.fromEnvironment();
        String url = envOr("ADMIN_DB_URL", "jdbc:postgresql://" + envOr("DB_HOST", "localhost") + ":" + envOr("DB_PORT", "5432") + "/" + envOr("DB_NAME", "kaarigar_setu"));
        String user = envOr("ADMIN_DB_USER", System.getProperty("user.name"));
        String secret = envOr("ADMIN_DB_PASSWORD", "");
        com.kaarigarsetu.config.ConnectionProvider provider = () -> DriverManager.getConnection(url, user, secret);
        java.io.Console console = System.console();
        Supplier<char[]> password = () -> console != null ? console.readPassword("Password: ")
                : new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8).nextLine().toCharArray();
        ModeratorAdminCli cli = new ModeratorAdminCli(new ModeratorDao(provider), new PhoneProtector(settings.phoneEncryptionKey(), settings.phoneHmacKey(), new SecureRandom()),
                new AuditLogDao(provider, com.kaarigarsetu.security.DataCipher.of(settings.dataKeys(), com.kaarigarsetu.security.DataCipher.AUDIT, false)), System.getProperty("user.name"), password, System.out, System.err);
        System.exit(cli.run(args));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
