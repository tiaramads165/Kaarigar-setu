package com.kaarigarsetu.credential;

import com.kaarigarsetu.security.SignatureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureKeyRotationTest {
    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static void retire(SignatureService old, Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(old.keyId() + ".pem"), old.publicKeyPem());
    }

    @Test
    void aCredentialSignedBeforeARotationStillVerifiesAfterwardsAndNewOnesUseTheNewKey(@TempDir Path dir) throws Exception {
        SignatureService oldKey = new SignatureService(rsa(2048));
        String signedByOld = oldKey.sign("{\"id\":\"old\"}");
        retire(oldKey, dir);

        SignatureService newKey = new SignatureService(rsa(2048)).withRetiredKeys(dir);
        assertTrue(newKey.verify("{\"id\":\"old\"}", signedByOld, oldKey.keyId()), "old credential must survive the rotation");
        assertFalse(newKey.verify("{\"id\":\"old\"}", signedByOld), "the plain check still means: signed by the ACTIVE key");
        String signedByNew = newKey.sign("{\"id\":\"new\"}");
        assertTrue(newKey.verify("{\"id\":\"new\"}", signedByNew, newKey.keyId()));
        assertEquals(java.util.List.of("active", "retired"), newKey.keys().stream().map(SignatureService.KeyInfo::status).toList());
    }

    @Test
    void anUnknownKeyIdNeverVerifiesAndAKeyCannotBeUsedUnderAnotherKeysId(@TempDir Path dir) throws Exception {
        SignatureService active = new SignatureService(rsa(2048));
        SignatureService attacker = new SignatureService(rsa(2048));
        String forged = attacker.sign("{\"id\":\"forged\"}");
        assertFalse(active.verify("{\"id\":\"forged\"}", forged, attacker.keyId()), "key id of a key we never trusted");
        assertFalse(active.verify("{\"id\":\"forged\"}", forged, active.keyId()), "attacker's signature claiming the real key id");
        assertFalse(active.verify("{\"id\":\"forged\"}", forged, "0000000000000000"));
    }

    @Test
    void aRetiredKeyFileWhoseNameLiesAboutItsKeyIsRefused(@TempDir Path dir) throws Exception {
        SignatureService victim = new SignatureService(rsa(2048));
        SignatureService attacker = new SignatureService(rsa(2048));
        Files.writeString(dir.resolve(victim.keyId() + ".pem"), attacker.publicKeyPem());      // the attacker's key filed under the victim's key id
        assertThrows(IllegalStateException.class, () -> new SignatureService(rsa(2048)).withRetiredKeys(dir));
    }

    @Test
    void aMissingDirectoryMeansNoRetiredKeys() throws Exception {
        SignatureService service = new SignatureService(rsa(2048)).withRetiredKeys(Path.of("does-not-exist"));
        assertEquals(1, service.keys().size());
    }
}
