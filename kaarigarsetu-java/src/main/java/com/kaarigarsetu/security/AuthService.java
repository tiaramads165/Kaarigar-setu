package com.kaarigarsetu.security;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.ModeratorDao;
import com.kaarigarsetu.dao.TrustedDeviceDao;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.model.TrustedDevice;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.AuthenticationFailedException;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.exception.ValidationException;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Login for the three roles. Workers and employers: phone number + OTP. Moderators: a NAMED account (username + PBKDF2 password hash from the moderators
 * table) PLUS an OTP sent to that person's own phone, so every action can be attributed to a person. Every outcome is audited, and unknown or disabled
 * accounts are indistinguishable from a wrong password so the API cannot be used to enumerate users.
 */
public class AuthService {
    /** What the browser sent back in its two cookies (either may be null): the trusted-device token and the code-binding token. */
    public record DeviceCookies(String deviceToken, String bindToken) {
        public static final DeviceCookies NONE = new DeviceCookies(null, null);
    }

    /** What the server asks the browser to remember after a code request ({@code bindToken} is null for a trusted device, which needs no binding). */
    public record OtpRequestOutcome(String bindToken) {}

    /** A signed-in user plus the trusted-device token the browser must hold from now on (the same one when it already had it). */
    public record LoginOutcome(AuthenticatedUser user, String deviceToken) {}

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{22,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Budgets. A TRUSTED device has its own; an UNKNOWN device shares the phone's, but per address first, so a stranger's burn stays on the stranger's address. */
    static final int TRUSTED_DEVICE_REQUESTS = 5;
    static final int TRUSTED_ADDRESS_REQUESTS = 30;
    static final int UNKNOWN_PHONE_ADDRESS_REQUESTS = 3;
    static final int UNKNOWN_PHONE_REQUESTS_PER_HOUR = 12;
    static final int UNKNOWN_ADDRESS_REQUESTS = 10;

    /** A fixed, valid-looking hash so an unknown moderator name costs the same time as a wrong password. */
    private static final String DUMMY_HASH = PasswordHasher.hash("not-a-real-password".toCharArray());
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final WorkerDao workerDao;
    private final EmployerDao employerDao;
    private final ModeratorDao moderatorDao;
    private final TrustedDeviceDao devices;
    private final ProofOfWork proofOfWork;
    private final PhoneProtector phones;
    private final OtpService otp;
    private final OtpSender sender;
    private final RateLimiter limiter;
    private final AuditLogger audit;
    private final AppSettings settings;

    public AuthService(WorkerDao workerDao, EmployerDao employerDao, ModeratorDao moderatorDao, TrustedDeviceDao devices, ProofOfWork proofOfWork, PhoneProtector phones,
                       OtpService otp, OtpSender sender, RateLimiter limiter, AuditLogger audit, AppSettings settings) {
        this.workerDao = workerDao;
        this.employerDao = employerDao;
        this.moderatorDao = moderatorDao;
        this.devices = devices;
        this.proofOfWork = proofOfWork;
        this.phones = phones;
        this.otp = otp;
        this.sender = sender;
        this.limiter = limiter;
        this.audit = audit;
        this.settings = settings;
    }

    /**
     * Sends a login code. Always returns normally for a well-formed number, whether or not an account exists, and a request for an unknown number looks
     * exactly like one for a known number.
     *
     * <p>Why the budgets are split (the targeted lock-out, harness AUTH-08): limits that belong to the PHONE NUMBER are shared by everyone who types it, so a
     * stranger could use them up, or lock the victim's pending code with wrong guesses, and keep the victim out. So:
     * <ul>
     *   <li>a TRUSTED DEVICE (a browser that already signed in as this account) has its own request budget and its own code; nothing done with the phone number
     *       by anyone else touches either;</li>
     *   <li>an UNKNOWN device first solves a small proof-of-work puzzle (a cost for every request), then counts against a budget for that phone AND that
     *       address (a stranger's burn stays on the stranger's address) and a per-phone hourly ceiling that bounds SMS cost and guessing;</li>
     *   <li>every code is bound to the browser that asked for it (its device, or a short-lived binding cookie), so one person requesting or guessing cannot
     *       retire, lock or use up another person's code.</li>
     * </ul>
     */
    public OtpRequestOutcome requestLoginOtp(Role role, String rawPhone, String clientIp, DeviceCookies cookies, ProofOfWork.Solution proof) {
        requirePhoneRole(role);
        String phone = PhoneProtector.normalize(rawPhone);
        String lookup = phones.lookupKey(phone);
        String ipHash = phones.ipHash(clientIp);
        Optional<Long> account = accountId(role, lookup);
        Optional<TrustedDevice> device = trustedDevice(role, account, cookies.deviceToken());
        String challenge;
        String bindToken = null;
        if (device.isPresent()) {
            limiter.check("otp:req:device:" + device.get().deviceId(), TRUSTED_DEVICE_REQUESTS, WINDOW);
            limiter.check("otp:req:ip:known:" + ipHash, TRUSTED_ADDRESS_REQUESTS, WINDOW);
            challenge = challengeKey(role, lookup) + ":d" + device.get().deviceId();
        } else {
            requireProofOfWork(ipHash, proof);
            limiter.check("otp:req:phone-ip:" + lookup + ":" + ipHash, UNKNOWN_PHONE_ADDRESS_REQUESTS, WINDOW);
            limiter.check("otp:req:phone:" + lookup, UNKNOWN_PHONE_REQUESTS_PER_HOUR, Duration.ofHours(1));
            limiter.check("otp:req:ip:" + ipHash, UNKNOWN_ADDRESS_REQUESTS, WINDOW);
            bindToken = usable(cookies.bindToken()) ? cookies.bindToken() : newToken();
            challenge = challengeKey(role, lookup) + ":b" + bindId(bindToken);
        }
        if (account.isPresent()) {
            sender.send(phone, "login", otp.issue(challenge, ipHash));
            audit.log("LOGIN_OTP_REQUESTED", role.name().toLowerCase() + ":" + account.get(), "channel=sms device=" + (device.isPresent() ? "trusted" : "unknown"));
        } else {
            audit.log("LOGIN_OTP_REQUESTED_UNKNOWN", "anonymous", "role=" + role.name() + " ip=" + ipHash.substring(0, 12));
            if (settings.appEnv().equals("dev")) {   // the HTTP reply stays identical; only the developer's server log explains the silence
                System.getLogger("ks.otp").log(System.Logger.Level.INFO,
                        "[CONSOLE OTP] NO CODE SENT: no {0} account is registered for phone ******{1}. Register first (or use /demo).",
                        role.name().toLowerCase(), phone.substring(Math.max(0, phone.length() - 4)));
            }
        }
        return new OtpRequestOutcome(bindToken);
    }

    /** An unknown device must have solved a puzzle of the current difficulty: harder for an address that keeps asking. Off when OTP_POW_BITS=0. */
    private void requireProofOfWork(String ipHash, ProofOfWork.Solution proof) {
        int base = settings.otpPowBits();
        if (base <= 0) return;
        int bits = Math.min(base + 4, base + 2 * limiter.count("otp:pow:ip:" + ipHash, WINDOW));      // 16, 18, 20 bits: the dearest is about a million tries
        proofOfWork.require(proof, bits, ipHash);
        limiter.check("otp:pow:ip:" + ipHash, Integer.MAX_VALUE, WINDOW);        // record the solved puzzle: the next one from this address is harder
    }

    /**
     * Checks the code against the challenge of THIS browser (its trusted device, or its binding cookie). A browser without either has no challenge, so guesses
     * from a stranger are refused without touching anyone's attempt counter. A successful sign-in remembers the browser as a trusted device.
     */
    public LoginOutcome verifyLogin(Role role, String rawPhone, String code, String clientIp, DeviceCookies cookies) {
        requirePhoneRole(role);
        String lookup = phones.lookupKey(PhoneProtector.normalize(rawPhone));
        String ipHash = phones.ipHash(clientIp);
        limiter.check("otp:verify:ip:" + ipHash, 30, WINDOW);
        Optional<Long> id = accountId(role, lookup);
        Optional<TrustedDevice> device = trustedDevice(role, id, cookies.deviceToken());
        String challenge = device.isPresent() ? challengeKey(role, lookup) + ":d" + device.get().deviceId()
                : challengeKey(role, lookup) + ":b" + (usable(cookies.bindToken()) ? bindId(cookies.bindToken()) : "none");
        try {
            otp.verify(challenge, code);   // verified even for unknown accounts (always fails, same message)
        } catch (InvalidOTPException exception) {
            audit.log("LOGIN_FAILED", id.map(i -> role.name().toLowerCase() + ":" + i).orElse("anonymous"), "reason=otp ip=" + ipHash.substring(0, 12));
            throw exception;
        }
        if (id.isEmpty()) throw new InvalidOTPException("OTP is unavailable, expired, or locked");
        audit.log("LOGIN_SUCCESS", role.name().toLowerCase() + ":" + id.get(), "method=otp device=" + (device.isPresent() ? "trusted" : "new"));
        String deviceToken;
        if (device.isPresent()) {
            devices.touch(device.get().deviceId());
            deviceToken = cookies.deviceToken();
        } else {
            deviceToken = newToken();
            devices.insert(role.name(), id.get(), phones.tokenHash("device", deviceToken), settings.maxTrustedDevices());
        }
        return new LoginOutcome(new AuthenticatedUser(role, id.get()), deviceToken);
    }

    private Optional<TrustedDevice> trustedDevice(Role role, Optional<Long> account, String deviceToken) {
        if (account.isEmpty() || !usable(deviceToken)) return Optional.empty();
        return devices.find(role.name(), account.get(), phones.tokenHash("device", deviceToken), settings.trustedDeviceDays());
    }

    private static boolean usable(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The binding token stands in the challenge key only as a short keyed hash, so the key reveals nothing usable. */
    private String bindId(String bindToken) {
        return phones.tokenHash("bind", bindToken).substring(0, 22);
    }

    /**
     * Development convenience: sign in as one of the accounts made by the demo seeder without an OTP. Refused (as if the route did not exist)
     * unless DEMO_LOGIN=true in APP_ENV=dev, and refused for any account that is not a seeded demo account, so it can never open a real one.
     */
    public AuthenticatedUser demoLogin(Role role, long accountId) {
        if (!settings.demoLoginEnabled() || !settings.appEnv().equals("dev")) throw new com.kaarigarsetu.exception.NotFoundException("Not found");
        requirePhoneRole(role);
        boolean demo = role == Role.WORKER ? workerDao.isDemoAccount(accountId) : employerDao.isDemoAccount(accountId);
        if (!demo) {
            audit.log("DEMO_LOGIN_REFUSED", "anonymous", "role=" + role.name() + " id=" + accountId);
            throw new com.kaarigarsetu.exception.ForbiddenException("Not a demo account");
        }
        audit.log("DEMO_LOGIN", role.name().toLowerCase() + ":" + accountId, "method=demo-seed");
        return new AuthenticatedUser(role, accountId);
    }

    /** Step 1 of moderator login: password check, then an OTP goes to that moderator's own phone. */
    public void moderatorStart(String username, char[] password, String clientIp) {
        String ipHash = phones.ipHash(clientIp);
        String name = safe(username);
        limiter.check("mod:login:ip:" + ipHash, 10, WINDOW);
        limiter.check("mod:login:user:" + name, 5, WINDOW);
        Optional<Moderator> account = moderatorDao.findByUsername(name).filter(Moderator::active);
        boolean passwordOk = PasswordHasher.matches(password, account.map(Moderator::passwordHash).orElse(DUMMY_HASH));   // the same work for a known and an unknown name
        if (account.isEmpty() || !passwordOk) {
            audit.log("LOGIN_FAILED", "moderator:" + name, "reason=password ip=" + ipHash.substring(0, 12));
            throw new AuthenticationFailedException("Invalid credentials");
        }
        String code = otp.issue("moderator:" + name, ipHash);
        sender.send(phones.decrypt(account.get().phoneEncrypted()), "moderator", code);
        audit.log("MODERATOR_OTP_ISSUED", "moderator:" + name, "ip=" + ipHash.substring(0, 12));
    }

    /** Step 2: the OTP. The session then carries this moderator's own id, so every later action is theirs. */
    public AuthenticatedUser moderatorVerify(String username, String code, String clientIp) {
        String ipHash = phones.ipHash(clientIp);
        String name = safe(username);
        limiter.check("mod:verify:ip:" + ipHash, 30, WINDOW);
        try {
            otp.verify("moderator:" + name, code);
        } catch (InvalidOTPException exception) {
            audit.log("LOGIN_FAILED", "moderator:" + name, "reason=otp ip=" + ipHash.substring(0, 12));
            throw exception;
        }
        Moderator account = moderatorDao.findByUsername(name).filter(Moderator::active).orElseThrow(() -> new AuthenticationFailedException("Invalid credentials"));
        audit.log("LOGIN_SUCCESS", "moderator:" + name, "method=password+otp");
        return new AuthenticatedUser(Role.MODERATOR, account.moderatorId());
    }

    private Optional<Long> accountId(Role role, String lookup) {
        return role == Role.WORKER
                ? workerDao.findByPhone(lookup).map(w -> w.workerId())
                : employerDao.findByPhone(lookup).map(e -> e.employerId());
    }

    private static String challengeKey(Role role, String lookup) {
        return "login:" + role.name() + ":" + lookup;
    }

    private static void requirePhoneRole(Role role) {
        if (role != Role.WORKER && role != Role.EMPLOYER) throw new ValidationException("Unsupported role");
    }

    private static String safe(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return (cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned).toLowerCase(java.util.Locale.ROOT);
    }
}
