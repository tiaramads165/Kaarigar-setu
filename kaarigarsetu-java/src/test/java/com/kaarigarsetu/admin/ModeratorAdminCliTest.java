package com.kaarigarsetu.admin;

import com.kaarigarsetu.dao.AuditLogDao;
import com.kaarigarsetu.dao.ModeratorDao;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.model.AuditLog;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.security.PasswordHasher;
import com.kaarigarsetu.security.PhoneProtector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModeratorAdminCliTest {
    private static byte[] key(int v) { byte[] b = new byte[32]; Arrays.fill(b, (byte) v); return b; }

    private final PhoneProtector phones = new PhoneProtector(key(1), key(2), new SecureRandom());
    private final ModeratorDao moderators = mock(ModeratorDao.class);
    private final AuditLogDao audit = mock(AuditLogDao.class);
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private String password = "a long enough password";

    private int run(String... args) {
        var cli = new ModeratorAdminCli(moderators, phones, audit, "ops-user", () -> password.toCharArray(), new PrintStream(outBytes), new PrintStream(errBytes));
        return cli.run(args);
    }

    private String out() { return outBytes.toString(); }
    private String err() { return errBytes.toString(); }

    @Test
    void addingAModeratorStoresAHashNotThePasswordAnEncryptedPhoneAndWhoDidIt() {
        when(moderators.insert(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new Moderator(1L, "alice", "Alice Rao", "h", new byte[0], true, java.time.LocalDateTime.now(), "operator:ops-user"));
        assertEquals(0, run("add", "alice", "Alice Rao", "9876543210"));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> phone = ArgumentCaptor.forClass(byte[].class);
        verify(moderators).insert(eq("alice"), eq("Alice Rao"), hash.capture(), phone.capture(), eq("operator:ops-user"));
        assertNotEquals(password, hash.getValue());
        assertTrue(PasswordHasher.matches(password.toCharArray(), hash.getValue()));
        assertEquals("+919876543210", phones.decrypt(phone.getValue()));
        ArgumentCaptor<AuditLog> event = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit).insert(event.capture());
        assertEquals("MODERATOR_ACCOUNT_CREATED", event.getValue().eventType());
        assertEquals("operator:ops-user", event.getValue().actor());
        assertFalse(out().contains("9876543210"), "the full phone number is never printed");
        assertFalse((out() + err()).contains(password));
    }

    @Test
    void badUsernamesWeakPasswordsAndBadPhonesAreRefusedBeforeAnythingIsWritten() {
        for (String bad : new String[]{"Alice", "al", "9lives", "has space", "a;drop", "x".repeat(41)}) assertEquals(1, run("add", bad, "Name", "9876543210"), bad);
        password = "short";
        assertEquals(1, run("add", "alice", "Alice", "9876543210"));
        password = "samesamesame";
        assertEquals(1, run("add", "samesamesame", "Same", "9876543210"), "the password must not be the username");
        password = "a long enough password";
        assertEquals(1, run("add", "alice", "Alice", "12345"));
        assertEquals(1, run("add", "alice", " ", "9876543210"));
        verify(moderators, never()).insert(anyString(), anyString(), anyString(), any(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void aDuplicateUsernameIsReportedNotCrashed() {
        when(moderators.insert(anyString(), anyString(), anyString(), any(), anyString())).thenThrow(new DataAccessException("Failed to create moderator", new java.sql.SQLException("duplicate key")));
        assertEquals(1, run("add", "alice", "Alice", "9876543210"));
        assertTrue(err().contains("duplicate key"));
        verifyNoInteractions(audit);
    }

    @Test
    void disablingEnablingAndChangingAPasswordAreAuditedAndAnUnknownNameIsAnError() {
        when(moderators.setActive("bob", false)).thenReturn(true);
        when(moderators.setActive("bob", true)).thenReturn(true);
        when(moderators.setPassword(eq("bob"), anyString())).thenReturn(true);
        when(moderators.countActive()).thenReturn(1);
        assertEquals(0, run("disable", "bob"));
        assertTrue(err().contains("warning: 1 active"), "disabling the second-last moderator warns that approvals cannot happen");
        assertEquals(0, run("enable", "bob"));
        assertEquals(0, run("set-password", "bob"));
        ArgumentCaptor<AuditLog> events = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit, times(3)).insert(events.capture());
        assertEquals(List.of("MODERATOR_ACCOUNT_DISABLED", "MODERATOR_ACCOUNT_ENABLED", "MODERATOR_PASSWORD_CHANGED"), events.getAllValues().stream().map(AuditLog::eventType).toList());
        assertEquals(1, run("disable", "nobody"));
        assertEquals(1, run("set-password", "nobody"));
        verify(audit, times(3)).insert(any());
    }

    @Test
    void listShowsNoPasswordHashesOrPhones() {
        when(moderators.findAll()).thenReturn(List.of(new Moderator(1L, "alice", "Alice Rao", "SECRET-HASH", new byte[]{1, 2}, true, java.time.LocalDateTime.now(), "operator:x"),
                new Moderator(2L, "bob", "Bob Iyer", "SECRET-HASH", new byte[]{3}, false, java.time.LocalDateTime.now(), "operator:x")));
        assertEquals(0, run("list"));
        assertTrue(out().contains("alice") && out().contains("DISABLED"));
        assertFalse(out().contains("SECRET-HASH"));
    }

    @Test
    void unknownOrIncompleteCommandsPrintUsageAndExitWithTwo() {
        assertEquals(2, run());
        assertEquals(2, run("add", "alice"));
        assertEquals(2, run("delete", "alice"));
        assertEquals(2, run("list", "extra"));
        assertTrue(err().contains("usage"));
    }
}
