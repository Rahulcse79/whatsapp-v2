# Security

Living document. It records the decisions §7 requires and the trade-offs behind them, so
a reviewer can judge the reasoning rather than only the code. Sections are added by the
tasks that make the decisions.

---

## Credential storage at rest (Task 16)

**Decision.** AES-256-GCM, with the key held by the **Android Keystore** under the alias
`whatsappv2.credentials.v1`. Implemented in `AesGcmCredentialCipher`.

### Why not Jetpack Security

`EncryptedSharedPreferences` and `EncryptedFile` are deprecated, and neither fits the
shape of the data: a SIP password belongs in the same Room row as the account it
protects, so the two can be written, migrated and deleted atomically. A separate
encrypted file would let an account and its credential drift apart — including the case
where deleting an account leaves its password behind. §7 permits "equivalent AES-GCM
wrapping" for exactly this reason.

### What is and is not implemented here

No cryptographic primitive is implemented. AES and GCM come from the platform provider.
The code chooses the mode, generates the IV, and frames the result.

| Choice | Value | Why |
|---|---|---|
| Algorithm | `AES/GCM/NoPadding` | Authenticated: tampering fails loudly instead of returning plausible rubbish that would then be sent as a SIP password |
| Key size | 256 bits | |
| IV | 12 bytes, fresh per encryption, from `SecureRandom` | GCM's recommended size. **IV reuse under one key breaks GCM catastrophically**, leaking the XOR of plaintexts and the authentication subkey |
| Tag | 128 bits | Full length; truncating weakens forgery resistance |
| Framing | `base64(version │ iv │ ciphertext+tag)` | The version byte lets a future algorithm change be *detected* rather than mis-decrypted — without it, old rows would fail as if tampered with |

### Trade-off: no user authentication requirement

`setUserAuthenticationRequired(true)` would bind decryption to a recent unlock. It is
**not** used, deliberately.

This app must decrypt credentials to re-register in the background, and the moment that
matters most is while the device is locked and a call is arriving. A key that could not
be used then would mean missed calls — the app's primary function failing precisely when
it is needed. `setUnlockedDeviceRequired(true)` is omitted for the same reason.

**What this does and does not buy.** The Keystore raises the cost of *offline*
extraction: on a device with a TEE or StrongBox the key material never enters the app
process, so reading the app's files is not enough. It does **not** defend against code
already running as this app on a rooted or compromised device — such an attacker can ask
the Keystore to decrypt. That is the accepted boundary.

### Key invalidation and recovery

A Keystore key can become permanently unusable: the secure lock screen is removed, app
data is restored onto a different device, or the keystore is corrupted.

This is a **state to recover from, not a bug**, which is why `CipherError` is a typed
value rather than an exception:

| Error | Meaning | Recovery |
|---|---|---|
| `KeyInvalidated` | The key is gone or unusable | Discard stored credentials, ask the user to re-enter the password |
| `AuthenticationFailed` | Ciphertext altered, or encrypted under a different key | Same — the stored value is unrecoverable |
| `KeyUnavailable` | Keystore itself failed | **Not** recoverable by re-entry; telling the user to retype would be a lie |
| `MalformedCiphertext` | Unknown version or corrupt framing | Treated as data loss |

`requiresReEntry` distinguishes the first two from the rest, so the UI prompts only when
prompting can actually help. Task 21 wires the prompt; Task 18 maps these onto domain
errors.

### Testing

The Android Keystore cannot be exercised on the JVM, so `SecretKeyProvider` is an
interface with the Keystore implementation in production and an in-memory key in test
source. Without that seam the encoding, IV behaviour and failure paths could only be
tested on a device — which in practice means not tested at all.

Covered on the JVM: round trip (ordinary, empty, 256-character, non-ASCII), IV uniqueness
across 200 encryptions, plaintext absence from the blob, version rejection, malformed
input, ciphertext tampering, IV tampering, key loss, and each error's recovery
classification. The Keystore interaction itself is verified on-device from Task 33.
