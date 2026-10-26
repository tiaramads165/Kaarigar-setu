package com.kaarigarsetu.security;

/** DEV/PILOT STUB: writes the code to the server log instead of sending an SMS. */
public final class ConsoleOtpSender implements OtpSender {
    private static final System.Logger LOG = System.getLogger("ks.otp");

    @Override
    public void send(String normalizedPhone, String purpose, String code) {
        LOG.log(System.Logger.Level.INFO, "[CONSOLE OTP] purpose={0} phone={1} code={2}", purpose,
                PhoneProtector.mask(normalizedPhone), code);
    }
}
