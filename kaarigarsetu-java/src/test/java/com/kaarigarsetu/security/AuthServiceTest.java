package com.kaarigarsetu.security;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.ModeratorDao;
import com.kaarigarsetu.dao.TrustedDeviceDao;
import com.kaarigarsetu.exception.ProofOfWorkRequiredException;
import com.kaarigarsetu.model.TrustedDevice;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.AuthenticationFailedException;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.exception.RateLimitExceededException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private static byte[] key(int v) { byte[] b = new byte[32]; Arrays.fill(b, (byte) v); return b; }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final PhoneProtector phones = new PhoneProtector(key(1), key(2), new SecureRandom());
    private final WorkerDao workers = mock(WorkerDao.class);
    private final EmployerDao employers = mock(EmployerDao.class);
    private final ModeratorDao moderators = mock(ModeratorDao.class);
    private final TrustedDeviceDao devices = mock(TrustedDeviceDao.class);
    private final ProofOfWork pow = new ProofOfWork("pow-key-pow-key-pow-key-pow-key".getBytes(), clock);
    private final OtpSender sender = mock(OtpSender.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final OtpService otp = new OtpService(new InMemoryOtpChallengeStore(), Transactions.none(), clock, new SecureRandom(), "p".getBytes(), 5, Duration.ofMinutes(5));
    private final String moderatorHash = PasswordHasher.hash("correct horse".toCharArray());
    private AuthService auth;
    private final String registered = phones.lookupKey("+919876543210");

    @BeforeEach
    void setUp() {
        auth = service(Map.of("OTP_POW_BITS", "0"), moderators);
        when(moderators.findByUsername("ops")).thenReturn(Optional.of(moderator(31L, "ops", true)));
        when(workers.findByPhone(registered)).thenReturn(Optional.of(new Worker(7L, "W", registered, "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        when(employers.findByPhone(registered)).thenReturn(Optional.of(new Employer(9L, "E", registered, false, null)));
    }

    private AuthService service(Map<String, String> env, ModeratorDao moderatorDao) {
        return new AuthService(workers, employers, moderatorDao, devices, pow, phones, otp, sender, new RateLimiter(clock), audit, AppSettings.of(env));
    }

    private static final String DEVICE_TOKEN = "D".repeat(43);

    /** Registers a browser as a trusted device of an account. */
    private void trust(Role role, long account, String token, long deviceId) {
        when(devices.find(eq(role.name()), eq(account), eq(phones.tokenHash("device", token)), anyInt())).thenReturn(Optional.of(new TrustedDevice(deviceId, role.name(), account, null, null)));
    }

    private AuthService.DeviceCookies trusted() {
        return new AuthService.DeviceCookies(DEVICE_TOKEN, null);
    }

    private AuthService.OtpRequestOutcome ask(Role role, String phone, String ip) {
        return auth.requestLoginOtp(role, phone, ip, AuthService.DeviceCookies.NONE, null);
    }

    private AuthService.OtpRequestOutcome ask(Role role, String phone, String ip, AuthService.DeviceCookies cookies) {
        return auth.requestLoginOtp(role, phone, ip, cookies, null);
    }

    private AuthService.LoginOutcome enter(Role role, String phone, String code, String ip, AuthService.DeviceCookies cookies) {
        return auth.verifyLogin(role, phone, code, ip, cookies);
    }

    private static AuthService.DeviceCookies bound(AuthService.OtpRequestOutcome outcome) {
        return new AuthService.DeviceCookies(null, outcome.bindToken());
    }

    private List<String> allSentCodes() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).send(anyString(), anyString(), code.capture());
        return code.getAllValues();
    }

    private Moderator moderator(long id, String username, boolean active) {
        return new Moderator(id, username, "Ops Person", moderatorHash, phones.encrypt("+919876500031"), active, null, "operator:test");
    }

    private String sentCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).send(anyString(), anyString(), code.capture());
        return code.getValue();
    }

    // ---- the dev-only demo sign-in

    private AuthService demoAuth(Map<String, String> env) {
        return service(env, moderators);
    }

    @Test
    void demoSignInDoesNotExistUnlessEnabledInDev() {
        for (Map<String, String> off : List.of(Map.<String, String>of(), Map.of("DEMO_LOGIN", "false"), Map.of("DEMO_LOGIN", "true", "APP_ENV", "prod"),
                Map.of("DEMO_LOGIN", "true", "APP_ENV", "staging"))) {
            assertThrows(NotFoundException.class, () -> demoAuth(off).demoLogin(Role.WORKER, 7), off.toString());
        }
        verifyNoInteractions(audit);
    }

    @Test
    void demoSignInOpensOnlySeededDemoAccountsAndNeverARealOne() {
        AuthService demo = demoAuth(Map.of("DEMO_LOGIN", "true", "APP_ENV", "dev"));
        when(workers.isDemoAccount(7L)).thenReturn(true);
        when(employers.isDemoAccount(9L)).thenReturn(true);
        assertEquals(new AuthenticatedUser(Role.WORKER, 7), demo.demoLogin(Role.WORKER, 7));
        assertEquals(new AuthenticatedUser(Role.EMPLOYER, 9), demo.demoLogin(Role.EMPLOYER, 9));
        verify(audit).log(eq("DEMO_LOGIN"), eq("worker:7"), anyString());

        assertThrows(ForbiddenException.class, () -> demo.demoLogin(Role.WORKER, 8), "a real worker");
        assertThrows(ForbiddenException.class, () -> demo.demoLogin(Role.EMPLOYER, 7), "a worker id used as an employer id");
        assertThrows(ForbiddenException.class, () -> demo.demoLogin(Role.WORKER, -1));
        verify(audit, times(3)).log(eq("DEMO_LOGIN_REFUSED"), eq("anonymous"), anyString());
        assertThrows(ValidationException.class, () -> demo.demoLogin(Role.MODERATOR, 0), "the moderator is never reachable this way");
    }

    @Test
    void workerAndEmployerLoginWithPhoneAndOtpAndTheBrowserIsRememberedAsATrustedDevice() {
        AuthService.OtpRequestOutcome asked = ask(Role.WORKER, "9876543210", "1.1.1.1");
        assertNotNull(asked.bindToken(), "an unknown browser gets a binding cookie for its code");
        AuthService.LoginOutcome in = enter(Role.WORKER, "+91 98765 43210", sentCode(), "1.1.1.1", bound(asked));
        assertEquals(new AuthenticatedUser(Role.WORKER, 7), in.user());
        assertNotNull(in.deviceToken());
        verify(devices).insert(eq("WORKER"), eq(7L), eq(phones.tokenHash("device", in.deviceToken())), anyInt());

        AuthService.OtpRequestOutcome askedAsEmployer = ask(Role.EMPLOYER, "9876543210", "1.1.1.1");
        assertEquals(new AuthenticatedUser(Role.EMPLOYER, 9), enter(Role.EMPLOYER, "9876543210", sentCode(), "1.1.1.1", bound(askedAsEmployer)).user());
    }
    @Test
    void aWorkerCodeCannotLogYouInAsAnEmployer() {
        AuthService.OtpRequestOutcome asked = ask(Role.WORKER, "9876543210", "ip");
        String code = sentCode();
        assertThrows(InvalidOTPException.class, () -> enter(Role.EMPLOYER, "9876543210", code, "ip", bound(asked)));
    }
    @Test
    void anUnknownNumberLooksExactlyLikeAKnownOneAndSendsNothing() {
        AuthService.OtpRequestOutcome unknownAsked = assertDoesNotThrow(() -> ask(Role.WORKER, "9123456789", "ip"));
        verifyNoInteractions(sender);
        InvalidOTPException unknown = assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9123456789", "123456", "ip", bound(unknownAsked)));
        AuthService.OtpRequestOutcome knownAsked = ask(Role.WORKER, "9876543210", "ip");
        assertNotNull(knownAsked.bindToken());
        assertNotNull(unknownAsked.bindToken(), "an unknown number gets the same cookie, so the reply gives nothing away");
        InvalidOTPException known = assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9876543210", "abcdef", "ip", bound(knownAsked)));
        // The exception messages differ (they only reach the audit log); what a caller sees is the HTTP status + code, which must be identical.
        assertEquals(codeSeenByClient(known), codeSeenByClient(unknown));
        assertEquals("401 invalid_otp", codeSeenByClient(unknown));
        verify(audit).log(eq("LOGIN_OTP_REQUESTED_UNKNOWN"), eq("anonymous"), anyString());
    }
    private static String codeSeenByClient(Throwable failure) {
        try {
            var response = mock(jakarta.servlet.http.HttpServletResponse.class);
            var body = new java.io.StringWriter();
            when(response.getWriter()).thenReturn(new java.io.PrintWriter(body));
            com.kaarigarsetu.web.ApiErrors.write(response, failure);
            var status = ArgumentCaptor.forClass(Integer.class);
            verify(response).setStatus(status.capture());
            String text = body.toString();
            assertFalse(text.contains(String.valueOf(failure.getMessage())), "no exception text in the body");
            return status.getValue() + " " + text.replaceAll(".*\"(?:error|code)\"\\s*:\\s*\"([a-z_]+)\".*", "$1").trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void fiveWrongGuessesLockTheCodeEvenForTheRealOwner() {
        AuthService.OtpRequestOutcome asked = ask(Role.WORKER, "9876543210", "ip");
        String code = sentCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9876543210", wrong, "ip", bound(asked)));
        assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9876543210", code, "ip", bound(asked)));
    }
    @Test
    void anUnknownDeviceIsLimitedPerPhoneAndAddressAndByAnHourlyCeilingForThePhone() {
        for (int i = 0; i < 3; i++) ask(Role.WORKER, "9876543210", "address-A");
        assertThrows(RateLimitExceededException.class, () -> ask(Role.WORKER, "9876543210", "address-A"), "a fourth from the same address");
        assertDoesNotThrow(() -> ask(Role.WORKER, "9876543210", "address-B"), "another address is not held up by address A's use");
        for (int i = 0; i < 3; i++) ask(Role.WORKER, "9876543210", "address-C");
        for (int i = 0; i < 3; i++) ask(Role.WORKER, "9876543210", "address-D");
        // 3 + 1 + 3 + 3 = 10 so far; two more reach the hourly ceiling of 12 for the phone across all addresses
        ask(Role.WORKER, "9876543210", "address-E");
        ask(Role.WORKER, "9876543210", "address-E");
        assertThrows(RateLimitExceededException.class, () -> ask(Role.WORKER, "9876543210", "address-F"), "the per-phone hourly ceiling");
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertDoesNotThrow(() -> ask(Role.WORKER, "9876543210", "address-F"));
    }
    @Test
    void theModeratorRoleCannotUseThePhoneFlow_andBadPhonesAreRejected() {
        assertThrows(ValidationException.class, () -> ask(Role.MODERATOR, "9876543210", "ip"));
        assertThrows(ValidationException.class, () -> enter(Role.MODERATOR, "9876543210", "123456", "ip", AuthService.DeviceCookies.NONE));
        assertThrows(ValidationException.class, () -> ask(Role.WORKER, "12", "ip"));
    }
    // ------------------------------------------------------------------ the targeted lock-out (AUTH-08)

    @Test
    void aStrangerBurningThePhonesBudgetCannotStopATrustedDeviceFromGettingItsCode() {
        trust(Role.WORKER, 7, DEVICE_TOKEN, 41);
        for (int i = 0; i < 12; i++) {
            try {
                ask(Role.WORKER, "9876543210", "attacker-" + (i / 3));          // four addresses, three requests each: the phone's whole hourly ceiling
            } catch (RateLimitExceededException ignored) {
                // the attacker is refused beyond the ceiling, which is the point
            }
        }
        assertThrows(RateLimitExceededException.class, () -> ask(Role.WORKER, "9876543210", "attacker-9"), "control: a new device is now held up");
        AuthService.OtpRequestOutcome asked = assertDoesNotThrow(() -> ask(Role.WORKER, "9876543210", "victim-home", trusted()));
        assertNull(asked.bindToken(), "a trusted device needs no binding cookie: it has its own code");
        assertEquals(new AuthenticatedUser(Role.WORKER, 7), enter(Role.WORKER, "9876543210", sentCode(), "victim-home", trusted()).user());
    }

    @Test
    void aTrustedDeviceHasItsOwnBudgetThatOthersCannotUse() {
        trust(Role.WORKER, 7, DEVICE_TOKEN, 41);
        for (int i = 0; i < 5; i++) ask(Role.WORKER, "9876543210", "home", trusted());
        assertThrows(RateLimitExceededException.class, () -> ask(Role.WORKER, "9876543210", "home", trusted()), "its own budget is finite too");
        trust(Role.WORKER, 7, "E".repeat(43), 42);
        assertDoesNotThrow(() -> ask(Role.WORKER, "9876543210", "work", new AuthService.DeviceCookies("E".repeat(43), null)), "a second trusted device has its own");
    }

    @Test
    void aStrangersWrongGuessesCannotLockAPendingCode() {
        trust(Role.WORKER, 7, DEVICE_TOKEN, 41);
        ask(Role.WORKER, "9876543210", "home", trusted());
        String code = sentCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 12; i++) {
            assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9876543210", wrong, "stranger", AuthService.DeviceCookies.NONE));
            assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9876543210", wrong, "stranger", new AuthService.DeviceCookies(null, "S".repeat(30))));
        }
        assertEquals(new AuthenticatedUser(Role.WORKER, 7), enter(Role.WORKER, "9876543210", code, "home", trusted()).user(), "the owner's code is untouched");
    }

    @Test
    void aStrangersRequestDoesNotRetireTheVictimsPendingCode() {
        AuthService.OtpRequestOutcome victim = ask(Role.WORKER, "9876543210", "victim-address");
        String victimsCode = sentCode();
        ask(Role.WORKER, "9876543210", "attacker-address");                          // the attacker asks for a code for the victim's number
        assertNotEquals(victim.bindToken(), ask(Role.WORKER, "9876543210", "attacker-address").bindToken(), "each browser gets its own binding");
        assertEquals(new AuthenticatedUser(Role.WORKER, 7), enter(Role.WORKER, "9876543210", victimsCode, "victim-address", bound(victim)).user());
    }

    @Test
    void aCodeAskedForByOneBrowserDoesNotWorkInAnother() {
        AuthService.OtpRequestOutcome mine = ask(Role.WORKER, "9876543210", "me");
        String code = sentCode();
        AuthService.OtpRequestOutcome theirs = ask(Role.WORKER, "9876543210", "them");
        assertThrows(InvalidOTPException.class, () -> enter(Role.WORKER, "9876543210", code, "them", bound(theirs)), "someone who only learned the code cannot use it in their own browser");
        assertDoesNotThrow(() -> enter(Role.WORKER, "9876543210", code, "me", bound(mine)));
    }

    @Test
    void aDeviceTokenOfAnotherAccountIsNotTrustedHere() {
        trust(Role.WORKER, 8, DEVICE_TOKEN, 50);                                      // a device of worker 8, not of worker 7
        AuthService.OtpRequestOutcome asked = ask(Role.WORKER, "9876543210", "ip", trusted());
        assertNotNull(asked.bindToken(), "treated as an unknown device for this phone");
    }

    @Test
    void aTrustedDeviceIsTouchedOnSignInAndNeverDuplicated() {
        trust(Role.WORKER, 7, DEVICE_TOKEN, 41);
        ask(Role.WORKER, "9876543210", "home", trusted());
        AuthService.LoginOutcome in = enter(Role.WORKER, "9876543210", sentCode(), "home", trusted());
        assertEquals(DEVICE_TOKEN, in.deviceToken(), "the same token stays in the browser");
        verify(devices).touch(41L);
        verify(devices, never()).insert(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void malformedCookiesAreIgnoredNotTrusted() {
        for (String junk : new String[]{"", "short", "has space in it but long enough to pass the length rule", "x'; DROP TABLE trusted_devices;--".repeat(3), null}) {
            AuthService.OtpRequestOutcome asked = ask(Role.WORKER, "9876543210", "ip-" + junk, new AuthService.DeviceCookies(junk, junk));
            assertNotNull(asked.bindToken());
        }
        verify(devices, never()).find(anyString(), anyLong(), anyString(), anyInt());
    }

    // ------------------------------------------------------------------ proof of work

    @Test
    void anUnknownDeviceMustSolveAPuzzleBeforeACodeIsSentAndATrustedOneNeedsNot() {
        AuthService hard = service(Map.of("OTP_POW_BITS", "8"), moderators);
        ProofOfWorkRequiredException asked = assertThrows(ProofOfWorkRequiredException.class,
                () -> hard.requestLoginOtp(Role.WORKER, "9876543210", "ip", AuthService.DeviceCookies.NONE, null));
        assertEquals(8, asked.bits());
        verifyNoInteractions(sender);
        ProofOfWork.Solution answer = new ProofOfWork.Solution(asked.challenge(), ProofOfWork.solve(new ProofOfWork.Challenge(asked.challenge(), asked.bits())));
        assertDoesNotThrow(() -> hard.requestLoginOtp(Role.WORKER, "9876543210", "ip", AuthService.DeviceCookies.NONE, answer));
        verify(sender).send(anyString(), eq("login"), anyString());
        trust(Role.WORKER, 7, DEVICE_TOKEN, 41);
        assertDoesNotThrow(() -> hard.requestLoginOtp(Role.WORKER, "9876543210", "ip", trusted(), null), "a trusted device is not asked to prove anything");
    }

    @Test
    void anUnknownNumberIsAskedForTheSamePuzzleSoThePuzzleGivesNothingAway() {
        AuthService hard = service(Map.of("OTP_POW_BITS", "8"), moderators);
        ProofOfWorkRequiredException known = assertThrows(ProofOfWorkRequiredException.class, () -> hard.requestLoginOtp(Role.WORKER, "9876543210", "ip", AuthService.DeviceCookies.NONE, null));
        ProofOfWorkRequiredException unknown = assertThrows(ProofOfWorkRequiredException.class, () -> hard.requestLoginOtp(Role.WORKER, "9123456789", "ip", AuthService.DeviceCookies.NONE, null));
        assertEquals(known.bits(), unknown.bits());
    }

    @Test
    void anAddressThatKeepsAskingGetsHarderPuzzlesUpToACap() {
        AuthService hard = service(Map.of("OTP_POW_BITS", "8"), moderators);
        int[] seen = new int[6];
        for (int i = 0; i < 6; i++) {
            ProofOfWorkRequiredException puzzle = assertThrows(ProofOfWorkRequiredException.class,
                    () -> hard.requestLoginOtp(Role.WORKER, "9123456789", "same-address", AuthService.DeviceCookies.NONE, null));
            seen[i] = puzzle.bits();
            ProofOfWork.Solution answer = new ProofOfWork.Solution(puzzle.challenge(), ProofOfWork.solve(new ProofOfWork.Challenge(puzzle.challenge(), puzzle.bits())));
            try {
                hard.requestLoginOtp(Role.WORKER, "9123456789", "same-address", AuthService.DeviceCookies.NONE, answer);
            } catch (RateLimitExceededException ignored) {
                // the address limit may bite; the puzzle was still solved and counted
            }
        }
        assertEquals(8, seen[0]);
        assertEquals(10, seen[1]);
        assertEquals(12, seen[2], "capped at the base plus four");
        assertEquals(12, seen[3]);
        assertEquals(12, seen[5]);
        AuthService fresh = hard;
        assertEquals(8, assertThrows(ProofOfWorkRequiredException.class, () -> fresh.requestLoginOtp(Role.WORKER, "9123456789", "another-address", AuthService.DeviceCookies.NONE, null)).bits(),
                "a different address is not made to pay for this one");
    }

    @Test
    void moderatorNeedsTheRightPasswordAndThenAnOtpAndTheSessionCarriesTheirOwnId() {
        assertThrows(AuthenticationFailedException.class, () -> auth.moderatorStart("ops", "wrong".toCharArray(), "ip"));
        assertThrows(AuthenticationFailedException.class, () -> auth.moderatorStart("nobody", "correct horse".toCharArray(), "ip"));
        verifyNoInteractions(sender);
        auth.moderatorStart("ops", "correct horse".toCharArray(), "ip");
        verify(sender).send(eq("+919876500031"), eq("moderator"), anyString());           // the code goes to THIS moderator's own phone
        assertThrows(InvalidOTPException.class, () -> auth.moderatorVerify("ops", "abcdef", "ip"));
        AuthenticatedUser user = auth.moderatorVerify("ops", sentCode(), "ip");
        assertEquals(Role.MODERATOR, user.role());
        assertEquals(31L, user.id(), "the session is that person's, not a shared account's");
    }

    @Test
    void usernamesAreCaseInsensitive() {
        auth.moderatorStart("OPS", "correct horse".toCharArray(), "ip");
        assertEquals(31L, auth.moderatorVerify("Ops", sentCode(), "ip").id());
    }

    @Test
    void aDisabledModeratorCannotSignInAndLooksLikeAWrongPassword() {
        when(moderators.findByUsername("gone")).thenReturn(Optional.of(moderator(32L, "gone", false)));
        assertThrows(AuthenticationFailedException.class, () -> auth.moderatorStart("gone", "correct horse".toCharArray(), "ip"));
        verifyNoInteractions(sender);
    }

    @Test
    void aModeratorDisabledBetweenThePasswordAndTheCodeIsRefusedAtTheSecondStep() {
        auth.moderatorStart("ops", "correct horse".toCharArray(), "ip");
        String code = sentCode();
        when(moderators.findByUsername("ops")).thenReturn(Optional.of(moderator(31L, "ops", false)));
        assertThrows(AuthenticationFailedException.class, () -> auth.moderatorVerify("ops", code, "ip"));
    }

    @Test
    void moderatorPasswordGuessingIsRateLimited() {
        for (int i = 0; i < 5; i++) assertThrows(AuthenticationFailedException.class, () -> auth.moderatorStart("ops", ("guess" + System.nanoTime()).toCharArray(), "ip" + System.nanoTime()));
        assertThrows(RateLimitExceededException.class, () -> auth.moderatorStart("ops", "correct horse".toCharArray(), "yet-another-ip"));
    }

    @Test
    void withNoModeratorAccountsAtAllLoginIsImpossibleNotOpen() {
        ModeratorDao empty = mock(ModeratorDao.class);
        when(empty.findByUsername(anyString())).thenReturn(Optional.empty());
        AuthService unconfigured = service(Map.of(), empty);
        assertThrows(AuthenticationFailedException.class, () -> unconfigured.moderatorStart("moderator", new char[0], "ip"));
        assertThrows(AuthenticationFailedException.class, () -> unconfigured.moderatorStart("moderator", "".toCharArray(), "ip2"));
    }

    @Test
    void auditedUsernamesCannotInjectLogLines() {
        assertThrows(AuthenticationFailedException.class, () -> auth.moderatorStart("ops\nLOGIN_SUCCESS actor=admin", "x".toCharArray(), "ip"));
        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq("LOGIN_FAILED"), actor.capture(), anyString());
        assertFalse(actor.getValue().contains("\n") || actor.getValue().contains(" "));
    }
}
