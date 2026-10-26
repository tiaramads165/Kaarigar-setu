# How a stranger can trust a credential (and how the issuer keeps that true)

A credential is useful only if the person relying on it can check it **without trusting the app, its server or the person showing it**. This page describes
what is signed, how anyone verifies it with their own tools, and how the issuer rotates keys and revokes credentials without breaking that.

## 1. What is signed

The shareable document (`GET /api/credential/{ref}/download`) has two parts:

```json
{ "credential": { "@context": [...], "id": "urn:uuid:...", "issuer": {"id": ".../api/credential/public-key", "keyId": "c4f6be3af005623e"},
                  "validFrom": "...", "validUntil": "...", "credentialSubject": { "id": "urn:ks:subject:...", "trade": "...", "skillScore": 81.3, ... } },
  "proof":      { "type": "RsaSignature2018-PKCS1v15-SHA256", "keyId": "c4f6be3af005623e", "canonicalization": "RFC8785-JCS", "signature": "<base64>" } }
```

The signature is RSA PKCS#1 v1.5 with SHA-256 over the **canonical form of `credential`** (RFC 8785, the JSON Canonicalization Scheme): members sorted, no
whitespace, minimal string escapes, numbers in shortest decimal form (so 81.30 and 81.3 are the same). The subject is a random pseudonym: no name, no phone number.

**Why canonical form matters.** The first version signed the exact text the server happened to write, including a score written `81.30`. Any ordinary JSON library
re-writes that as `81.3`, so the signature failed for everyone except software that kept the original bytes. The attack harness found this (CRED-01). Signing a canonical
form means a verifier in any language can parse the file, canonicalise it and check the signature. Credentials issued before the change carry `"canonicalization": "none"`
and the exact signed text in `proof.signedPayload`; they still verify.

Profile limits: numbers must have at most 15 significant digits and lie in [1e-6, 1e21); anything else is refused rather than guessed.

## 2. Verifying with nothing but the file and a key

```bash
python3 scripts/verify_credential.py credential.json --public-key issuer.pem --revocations revocations.json
python3 scripts/verify_credential.py credential.json --server https://issuer.example      # fetches the key and the list (trusts that server for the key)
```

`scripts/verify_credential.py` is standard-library Python plus the `openssl` command and **shares no code with the Java application**, which is the point: it was written
from this page. It checks, and reports each check:

1. the signature over the rebuilt bytes;
2. that the public key's fingerprint (first 16 hex digits of SHA-256 of its DER encoding) equals `proof.keyId` and `issuer.keyId` inside the signed data;
3. `validFrom <= now <= validUntil`;
4. with a revocation list: that the list itself carries a valid signature, is recent (default 24 hours) and does not contain this credential.

Exit status 0 = all passed, 1 = a check failed, 2 = could not run. Tests: `scripts/e2e.py` section 7 (accepts; rejects an edited score; accepts a re-written copy; rejects after revocation) and the
attack harness CRED-01, CRED-02, CRED-06.

**Trusting the key.** The key is public (`GET /api/credential/keys`, `GET /api/credential/public-key`), but a key fetched from the same server you are checking only proves the
document was not altered in transit or by anyone without the signing key; a hostile server could serve its own key. For anything that matters, get the key once through a channel you
trust (the issuer's website over HTTPS, a printed fingerprint) and pin it with `--public-key`.

## 3. Revocation and expiry

Every new issuance supersedes (revokes) the worker's earlier credentials, so a stale score cannot be replayed; a worker or moderator can also revoke one. The application checks
revocation and expiry on every online verification. For offline verifiers, `GET /api/credential/revocations` returns a **signed list** (`{"list": {...}, "proof": {...}}`, newest 5,000
entries, no free-text reasons). The signature stops a proxy or cache from adding, removing or re-dating entries (attack harness CRED-06); the list's age tells the verifier how stale it is.

## 4. Rotating the signing key

```bash
KEYSTORE_PASSWORD=... ./scripts/rotate-signing-key.sh        # then restart the application
```

The script saves the old **public** key as `data/keys/retired/<keyId>.pem`, archives the old keystore (mode 600; keep it offline or destroy it) and creates a new key pair. The application loads
every file in `RETIRED_KEYS_DIR`, refuses a file whose name does not match the fingerprint of the key inside it (a key cannot be filed under another key's id), and verifies each credential
with the key named by its own key id. Therefore credentials signed before a rotation keep verifying, new ones carry the new key id, and an unknown key id never verifies.
Tested by `SignatureKeyRotationTest` (and the script on a scratch keystore).

**Not done:** there is no trusted timestamp, so a credential cannot prove *when* it was signed independently of the issuer; a key compromise would need a revocation of everything signed
by that key (publishing the key as compromised in the list is not implemented).

## 5. Is the person showing it the owner?

A fixed QR link cannot say. The worker's signed-in session can mint a **live code** (`GET /api/credential/{ref}/live-qr`): an HMAC token bound to one credential that stops counting as live
after five minutes. The verification page says "Shown live", "Not live" (older than five minutes, e.g. a saved screenshot), "Do not trust this code" (not a valid code for this credential) or
"Static link" (no code). It proves the worker's own account produced the code a moment ago; it does not stop a worker from lending an unlocked phone, so for anything important compare the person
in the flesh. Tests: `PresentationTokensTest`, e2e section 7, attack harness CRED-07.
