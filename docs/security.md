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

---

## Call recording (Task 58)

Recording is **architecture, consent and an indicator** — not a shipped feature that
records by default. §2.6 forbids shipping a silent recorder, and everything below exists
to make one impossible rather than merely discouraged.

### What Android will and will not let this app capture

**It will not give you the other party from the system audio path.**
`MediaRecorder.AudioSource.VOICE_CALL` and its relatives are refused to ordinary apps and
have been since Android 10; they are available to the platform dialer and to privileged
builds, and to nothing else.

What *is* possible is recording the SIP media this app handles itself, which liblinphone
does through `Call.startRecording()`. That covers both directions of a SIP call, because
both streams pass through this process — but it covers only SIP calls placed by this app.
A cellular call happening at the same time is not recorded and cannot be.

This distinction is not a footnote. A user told "call recording" who later discovers they
have one side of a conversation has been misled, and a user who assumes their cellular
calls are covered has been misled in the more dangerous direction. The consent dialog says
which call is being recorded, by name.

### Consent

`RecordingConsent` is **per call** and starts at `None` on every one of them. There is no
setting, and deliberately so: a preference called "record calls", switched on once, is
precisely the silent recorder §2.6 forbids — it records every later call with nobody in
the room aware of it.

`RecordingPolicy.mayRecord` is the only gate, and it is a pure function. `CallRecorder`
takes the consent as a *parameter* rather than looking one up, so no implementation can
find a path that starts without one, and the policy re-checks that the consent names this
call — consent given on a previous call does not carry.

### Two-party consent, plainly

Many jurisdictions require **every** party to a call to agree, not just the person holding
the phone. A non-exhaustive sketch: several US states (California, Florida, Illinois,
Pennsylvania and others) require all-party consent; the UK permits recording for personal
use but not disclosure without consent; under GDPR a recording is personal data and
usually special-category data, needing a lawful basis, a retention period, and a route for
a subject access request.

This app **cannot obtain the far end's consent** and does not pretend to.
`RecordingConsent.GrantedByLocalUser` is named for exactly who agreed, so nothing
downstream can read it as more than it is, and the consent dialog tells the user in as
many words that the other party is not being asked and that telling them may be required
of them. Announcing the recording to the far end — a spoken notice or a periodic tone — is
**not implemented**, and any deployment operating under all-party consent needs it before
this feature is enabled.

### At rest

Recordings are the most sensitive artefact this app produces: they contain whatever was
said, including the DTMF that §7 forbids logging.

- **Encrypted.** `EncryptedRecordingStore` seals each finished file with AES-GCM under a
  key that never leaves the Android Keystore, and **deletes the plaintext before reporting
  success**.
- **The plaintext window is real and is closed.** liblinphone can only write a plain file,
  so one exists inside the app's private `filesDir` for the length of the call. A crash in
  that window leaves a `.tmp`, which the store sweeps on its next construction rather than
  leaving to be found later.
- **Never backed up.** `android:allowBackup="false"` plus `data_extraction_rules.xml`
  excluding every domain. Both, so a future decision to allow backup of *some* data cannot
  quietly start copying recordings off the device.
- **No path leaves the module.** `CallRecorder` returns a `Recording` with an id, a
  duration and a size — and no filename. Nothing above `:data:sip` can name a recording's
  location, so nothing above it can hand that location to anything else.

### Retention

`CallRecorder.purgeOlderThan` is a **hook, not a schedule**. What the retention period is
belongs to whoever deploys this app, and in several jurisdictions to their regulator;
hard-coding ninety days here would be this app inventing a legal position on its
operator's behalf. Nothing calls it automatically yet — that is a deployment decision and
an open question for the stakeholder.

### The indicator

`CallRecorder.active` is a `StateFlow` of the calls being recorded, and the in-call banner
renders from it. It is state rather than an event on purpose: an indicator driven by
start/stop events shows nothing at all after a process restart, and a recording running
with no indicator is the thing §2.6 forbids. The banner is a `liveRegion`, so a screen
reader announces it — a recording that starts silently for a blind user is a silent
recording.

### What is verified, and where

On the JVM: the consent gate in every combination (no consent, another call's consent, a
call with no media, an unsupported platform), that the stack is never asked when the gate
refuses, that the indicator cannot disagree with what is being written, that a call ending
by *any* route seals its recording, and that the retention hook reaches the store.

On a device: the encryption round trip and the plaintext deletion, which need the Android
Keystore and are therefore out of reach of the JVM suite — the same seam and the same
reasoning as `SecretKeyProvider` above.
