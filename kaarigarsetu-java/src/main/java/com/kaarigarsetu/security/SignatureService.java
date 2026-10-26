package com.kaarigarsetu.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * RSA PKCS#1 v1.5 / SHA-256 signing for portable credentials.
 *
 * <p>In the application the key pair comes from a PKCS#12 keystore created once at deploy time
 * (scripts/generate-signing-key.sh) and kept OUTSIDE the repository, so credentials issued today still verify
 * after a restart. The no-argument constructor builds a throw-away key pair and exists for unit tests only.
 */
public final class SignatureService {
    private static final int MIN_KEY_BITS = 2048;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    /** Public halves of earlier signing keys, by key id: credentials signed before a rotation keep verifying. */
    private final Map<String, PublicKey> retired = new LinkedHashMap<>();

    /** One entry of the public key list (GET /api/credential/keys). */
    public record KeyInfo(String keyId, String status, String algorithm, String publicKeyPem) {
    }

    /** Ephemeral key pair: tests and demos only. */
    public SignatureService() {
        this(generateKeyPair(2048));
    }

    public SignatureService(KeyPair keyPair) {
        if (!(keyPair.getPublic() instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalArgumentException("An RSA key of at least " + MIN_KEY_BITS + " bits is required");
        }
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        this.keyId = fingerprint(publicKey);
    }

    /** Loads the issuer key pair from a PKCS#12 keystore. Fails fast if it is missing or unreadable. */
    public static SignatureService fromKeyStore(Path keystore, char[] password, String alias) {
        if (!Files.isRegularFile(keystore)) {
            throw new IllegalStateException("Signing keystore not found at " + keystore
                    + " (create it once with scripts/generate-signing-key.sh)");
        }
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            Key key = store.getKey(alias, password);
            if (!(key instanceof PrivateKey privateKey) || store.getCertificate(alias) == null) {
                throw new IllegalStateException("No signing key under alias '" + alias + "' in " + keystore);
            }
            return new SignatureService(new KeyPair(store.getCertificate(alias).getPublicKey(), privateKey));
        } catch (IOException | java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to load signing keystore (wrong password or corrupt file)", exception);
        }
    }

    public String sign(String payload) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign credential", exception);
        }
    }

    /**
     * Loads retired public keys from a directory of {@code <keyId>.pem} files (created by scripts/rotate-signing-key.sh). A file whose name does not
     * match the fingerprint of the key inside it is refused, so a key cannot be slipped in under another key's id.
     */
    public SignatureService withRetiredKeys(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return this;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".pem")).sorted().toList()) {
                PublicKey key = parsePublicKeyPem(Files.readString(file, StandardCharsets.UTF_8));
                String id = fingerprint(key);
                String claimed = java.util.Objects.requireNonNull(file.getFileName()).toString().replaceFirst("\\.pem$", "");
                if (!id.equals(claimed)) {
                    throw new IllegalStateException("Retired key file " + file + " holds key " + id + ", not " + claimed);
                }
                if (!id.equals(keyId)) retired.put(id, key);
            }
        } catch (IOException | java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to read retired signing keys from " + directory, exception);
        }
        return this;
    }

    /** Verifies against the key that signed it: the current key, or a retired one. An unknown key id never verifies. */
    public boolean verify(String payload, String encodedSignature, String signerKeyId) {
        PublicKey key = signerKeyId == null || signerKeyId.isBlank() || signerKeyId.equals(keyId) ? publicKey : retired.get(signerKeyId);
        return key != null && verifyWith(key, payload, encodedSignature);
    }

    public List<KeyInfo> keys() {
        List<KeyInfo> all = new ArrayList<>();
        all.add(new KeyInfo(keyId, "active", algorithm(publicKey), pem(publicKey)));
        retired.forEach((id, key) -> all.add(new KeyInfo(id, "retired", algorithm(key), pem(key))));
        return all;
    }

    /** Never throws: any malformed input simply fails verification. */
    public boolean verify(String payload, String encodedSignature) {
        return verifyWith(publicKey, payload, encodedSignature);
    }

    private static boolean verifyWith(PublicKey key, String payload, String encodedSignature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException | java.security.ProviderException exception) {
            return false;
        }
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /** Short id of the signing key (SHA-256 of the public key), stored with each credential to support rotation. */
    public String keyId() {
        return keyId;
    }

    public String publicKeyPem() {
        return pem(publicKey);
    }

    private static String pem(PublicKey key) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    private static String algorithm(PublicKey key) {
        return key instanceof RSAPublicKey rsa ? "RSA-" + rsa.getModulus().bitLength() : key.getAlgorithm();
    }

    private static PublicKey parsePublicKeyPem(String pem) throws java.security.GeneralSecurityException {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalArgumentException("An RSA key of at least " + MIN_KEY_BITS + " bits is required");
        }
        return key;
    }

    private static String fingerprint(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())).substring(0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static KeyPair generateKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate RSA key pair", exception);
        }
    }
}
