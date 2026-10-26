package com.kaarigarsetu.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class SignatureServiceKeystoreTest {
    @TempDir
    Path dir;

    /** Creates a real PKCS#12 keystore with the JDK's own keytool, exactly as scripts/generate-signing-key.sh does. */
    private Path createKeystore(String password, int bits) throws Exception {
        Path keystore = dir.resolve("issuer.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "credential-signing", "-keyalg", "RSA", "-keysize", String.valueOf(bits), "-validity", "30",
                "-dname", "CN=Test Issuer", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", password, "-keypass", password)
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
        return keystore;
    }

    @Test
    void aCredentialSignedBeforeARestartStillVerifiesAfterIt() throws Exception {
        Path keystore = createKeystore("test-password", 2048);
        SignatureService before = SignatureService.fromKeyStore(keystore, "test-password".toCharArray(), "credential-signing");
        String signature = before.sign("{\"skillScore\":90}");

        SignatureService afterRestart = SignatureService.fromKeyStore(keystore, "test-password".toCharArray(), "credential-signing");
        assertTrue(afterRestart.verify("{\"skillScore\":90}", signature));
        assertEquals(before.keyId(), afterRestart.keyId());
        assertFalse(new SignatureService().verify("{\"skillScore\":90}", signature), "a different (ephemeral) key must not verify it");
    }

    @Test
    void aMissingKeystoreOrWrongPasswordFailsFast() throws Exception {
        assertThrows(IllegalStateException.class, () -> SignatureService.fromKeyStore(dir.resolve("none.p12"), "x".toCharArray(), "a"));
        Path keystore = createKeystore("right-password", 2048);
        assertThrows(IllegalStateException.class, () -> SignatureService.fromKeyStore(keystore, "wrong-password".toCharArray(), "credential-signing"));
        assertThrows(IllegalStateException.class, () -> SignatureService.fromKeyStore(keystore, "right-password".toCharArray(), "no-such-alias"));
    }

    @Test
    void aOneByteTamperFailsVerification() {
        SignatureService service = new SignatureService();
        String payload = "{\"worker\":\"x\",\"score\":90}";
        String signature = service.sign(payload);
        assertTrue(service.verify(payload, signature));
        assertFalse(service.verify(payload.replace("90", "91"), signature));
        byte[] raw = java.util.Base64.getDecoder().decode(signature);
        raw[raw.length - 1] ^= 1;
        assertFalse(service.verify(payload, java.util.Base64.getEncoder().encodeToString(raw)));
    }

    @Test
    void garbageSignaturesNeverThrow() {
        SignatureService service = new SignatureService();
        for (String bad : new String[]{"", "!!!not base64!!!", "AAAA", "A".repeat(2000)}) assertFalse(service.verify("{}", bad));
    }

    @Test
    void weakKeysAreRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair weak = generator.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> new SignatureService(weak));
    }

    @Test
    void publishesAPemPublicKey() {
        String pem = new SignatureService().publicKeyPem();
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n") && pem.trim().endsWith("-----END PUBLIC KEY-----"));
        assertFalse(pem.contains("PRIVATE"));
    }

    @Test
    void keyIdIsStablePerKeyAndDiffersBetweenKeys() {
        SignatureService a = new SignatureService();
        assertEquals(a.keyId(), a.keyId());
        assertEquals(16, a.keyId().length());
        assertNotEquals(a.keyId(), new SignatureService().keyId());
        assertTrue(new File(System.getProperty("java.home"), "bin/keytool").exists());
    }
}
