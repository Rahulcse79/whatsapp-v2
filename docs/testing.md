# Testing

How the suites are split, how to point the integration tests at a SIP server, and which
extensions are reserved so an automated run and a person cannot collide.

Written for Task 32. The decision behind it is **ADR-005**: integration tests run against
an already-deployed FreeSWITCH, not a container started per run.

## 1. Two suites, and only one of them gates a push

| | Runs | Needs a server | Gates every push |
|---|---|---|---|
| Unit tests + `FakeSipEngine` journeys | `./gradlew build` | no | **yes** |
| Integration tests | `./gradlew :data:sip:connectedAndroidTest` | yes | no |

This split is the point of the task. The server is shared and non-hermetic — someone can
be signed in on a handset while a run is going — so making every push depend on it would
turn an unrelated person's phone into a build failure. CI stays green **without
reachability to any SIP server**, which is what ADR-005 requires and what
`.github/workflows/ci.yml` does.

Integration tests run from `.github/workflows/integration.yml`, on demand or on a
schedule, with a **concurrency group of one** so two runs cannot use the same extension at
the same time.

## 2. Pointing the tests at a server

Nothing about the target is committed. The host is a LAN address that changes with the
network and the password is a credential; either one in git is a leak that outlives the
commit that removed it. Both are supplied at build time and the CI step **"Assert no test
target is committed"** fails the build if they reappear in the tree.

Set them in `~/.gradle/gradle.properties` — outside the repository, so it cannot be
committed by accident:

```properties
sip.test.host=192.168.1.10
sip.test.domain=192.168.1.10
sip.test.port=5060
sip.test.extension=1018
sip.test.extension.secondary=1019
sip.test.password=your-extension-password
```

Or pass them per-invocation, which is what CI does through secrets:

```bash
./gradlew :data:sip:connectedAndroidTest -Psip.test.host=192.168.1.10 -Psip.test.extension=1018
```

Each property also has an environment-variable form, upper-cased with dots as underscores
— `SIP_TEST_HOST`, `SIP_TEST_PASSWORD`, and so on. That is how a CI secret arrives.

**Missing configuration skips, it does not fail.** With nothing set, the instrumented
tests report themselves skipped and name the properties to set. A test that fails because
it was never told where to point says nothing about the code, and a suite that cries wolf
gets ignored.

## 3. Reserved extensions

The deployed instance carries extensions **1000–1019**.

| Range | Owner | Rule |
|---|---|---|
| 1000–1017 | people | Manual testing. Automation never touches these. |
| **1018, 1019** | **automation** | Reserved. Do not sign a handset in on either. |
| 3000 | shared | Conference room, `mod_conference` dial-in (Tasks 59–61). |

Two are reserved rather than one because Task 35 onward needs a call between two
endpoints, and a call needs both ends. This is ADR-005's mitigation for a shared server: if
automation owns its extensions exclusively, a manual session cannot make a run fail and
leave no trace of why.

## 4. Transports

UDP and TCP on **5060** are available and are what Task 33 covers.

**TLS is not currently available.** The internal profile has `internal_ssl_enable=false`
and the server's `tls/` directory holds only DTLS-SRTP and WSS certificates — there is no
SIP TLS certificate to trust. Enabling it means running `gentls_cert` on the server and
flipping the profile, which is a change to the deployment rather than to this repository.
It is tracked as **Q9** in `docs/architecture.md`, and until it is done Task 33's
"all three transports" done-when covers two of three and says so.

Registering over TLS against a server with TLS switched off does not produce a meaningful
failure — it produces a connection refused, which tests nothing about the client.

## 5. Running the integration suite from a clean checkout

1. Have the FreeSWITCH instance running and reachable from the device or emulator. An
   emulator reaches the host machine at `10.0.2.2`, **not** at `localhost`; a physical
   handset needs the machine's LAN address and the same Wi-Fi network.
2. Put the properties from §2 in `~/.gradle/gradle.properties`.
3. Start an emulator, or attach a device with USB debugging on.
4. `./gradlew :data:sip:connectedAndroidTest`

Reports land in `data/sip/build/reports/androidTests/connected/`.

**If the run fails with `ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner`,**
add `androidTestImplementation(libs.androidx.test.runner)` and a catalog entry for it. The
suite deliberately declares only artifacts the version catalog already pins: it cannot run
in the push gate — it needs a device and a reachable registrar — so an unverified new pin
here would put the gate at risk for code the gate never executes.

## 6. What the integration tests deliberately do not do

**They do not restart the registrar.** ADR-005 prefers a client-side transport drop, since
restarting a shared server disrupts anyone else using it. Recovery after a *real* registrar
restart is a scheduled manual test, not something a run does unasked.

**They do not assert timing to the second.** The backoff is deliberately jittered (§2.1),
so an integration test that asserted an exact delay would be asserting the random number
generator. The exact sequence is asserted on the JVM in
`RegistrationRecoveryCoordinatorTest`, where the randomness is injected; what integration
proves is that recovery happens at all, against a real stack and a real server.
