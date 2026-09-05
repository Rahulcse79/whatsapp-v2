# Network-change recovery — behaviour and log evidence

Task 30 (§6, DoD 6). What the client does when the network moves underneath it, and the
log lines that prove it.

## The distinction everything here is built around

"No network" and "the registrar is down" look identical from the account's point of view —
both leave it unregistered — and they call for opposite behaviour.

| Situation | What the client does | Why |
|---|---|---|
| No network | **Stops.** No timers, no attempts. | A REGISTER with no network cannot succeed, and each attempt wakes the radio. The platform's `ConnectivityManager` callback restarts things, not a timer of ours. |
| Network changed | **Rebinds the transports, then re-registers immediately.** | The binding on the old link is already dead and the sockets are bound to an address the device no longer holds. Waiting out a backoff earned by a network that is gone punishes the user for the platform's behaviour. |
| Network fine, registrar down | **Retries behind a growing backoff.** | The server is the problem, and 5,000 clients returning together is what knocks a recovering registrar over again (§2.1). |
| Credentials rejected | **Nothing.** | No amount of waiting makes a wrong password correct. The account waits for the edit that fixes it, which re-registers on its own (Task 29). |
| Logged out | **Nothing.** | Recovery is for connections that broke, not for decisions the user made. |

## Where it lives

- `RegistrationRecoveryPolicy` (`:domain`) — pure. Decides *what* should happen.
- `RegistrationRecoveryCoordinator` (`:data:sip`) — holds the bookkeeping and the timers,
  debounces the network, and drives the stack.
- `ConnectivityNetworkMonitor` (`:data:sip/network/platform`) — the only class that touches
  `ConnectivityManager`, isolated for the same reason as the liblinphone gateway.

Recovery is owned by the SIP engine and starts and stops with it, **not** by `:app`. It has
to outlive the foreground service, because the case it exists for — no network, so nothing
registered, so the service stops itself (§6) — is exactly when the service is gone. When
the *process* is dead nothing runs at all, and recovery is then push's job (ADR-004,
Task 38).

## Backoff evidence

Captured by `RegistrationRecoveryCoordinatorTest`, which pins the backoff's deliberate
randomness by handing it a `Random` that always samples the top of the window. The
sequence below is therefore an **assertion**, not a sample — it fails the build if the
attempt count stops climbing.

Scenario: the account is registered on Wi-Fi, the link stays up, and the registrar stops
answering.

```
Network is WIFI#1
Registrar unreachable for acct-1: retry 1 in 60s
Re-registering acct-1 (retry 1)
Re-register of acct-1 refused: Timeout
Registrar unreachable for acct-1: retry 2 in 120s
Re-registering acct-1 (retry 2)
Re-register of acct-1 refused: Timeout
Registrar unreachable for acct-1: retry 3 in 240s
Re-registering acct-1 (retry 3)
Re-register of acct-1 refused: Timeout
Registrar unreachable for acct-1: retry 4 in 480s
Re-registering acct-1 (retry 4)
Re-register of acct-1 refused: Timeout
Registrar unreachable for acct-1: retry 5 in 960s
```

The doubling is `RegistrationBackoff`'s window; in production the delay is a random sample
from `[0, window]` rather than the window itself, so no two clients return together. The
60-second base is the test's, chosen so a retry cannot fire inside a debounce window and
confuse "cancelled" with "not due yet"; production starts at two seconds.

Note what is **absent** after the fifth line: nothing re-registers on its own schedule, and
the attempt number never resets. A counter that reset would look identical for the first
two lines and then hold the client at 60 seconds for ever.

### Airplane mode on, then off

```
Network is WIFI#1
No network: retries stopped until one returns          <- radio off
                                                        (nothing at all for 10 minutes)
Network changed: unavailable -> WIFI#77                <- radio on, new connection id
Re-registering acct-1 (network changed)
```

The new id is not a test artefact: the platform issues one every time a network is
established, so returning to the same access point still counts as a different connection —
which is correct, because the source address and every SIP binding are new.

### Wi-Fi to cellular handover

```
Network changed: WIFI#1 -> CELLULAR#2
Re-registering acct-1 (network changed)
```

The transports are rebuilt (`setNetworkReachable(false)` then `true`) *before* the
REGISTER. A REGISTER sent first leaves from an interface the device no longer owns and
never reaches the wire.

### A flapping link

No output. A status that has not held still for a second is not believed, so a link
oscillating at the edge of Wi-Fi coverage produces no rebind and no REGISTER at all. A flap
that lands back where it started is not even a change, because the debounce runs *before*
the duplicate filter.

## Status of this evidence

These lines are produced and asserted on the JVM, against `FakeSipEngine` and a fake
network monitor. That is what makes them reproducible; it is also their limit. The two
classes that cannot run there — `ConnectivityNetworkMonitor` and the liblinphone gateway's
`setNetworkReachable` — are verified on-device from Task 33, and this document should gain
a `logcat` capture from a real handover at that point.
