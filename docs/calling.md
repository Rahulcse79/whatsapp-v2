# Calling — how a call actually happens

Tasks 35–43 (§3, §5.2, DoD 7 and 8). What each layer owns, and why the seams are where
they are.

## The path of one outgoing call

```
DialerScreen  ->  PlaceCallUseCase  ->  SipEngine.placeCall
   (:feature:dialer)   (:domain)            (:data:sip)
                                               |
                          1. publish the snapshot in Outgoing.Calling
                          2. ask PlatformCallRegistry  ->  TelecomCallRegistry (:app)
                                                              -> TelecomManager.placeCall
                                                              -> SipConnectionService creates
                                                                 the Connection, or Telecom refuses
                          3. only then: INVITE
```

**The order in step 2 is the whole point.** Telecom knows about the cellular call this app
cannot see. §3 names hand-rolled call handling a rejected design precisely because an app
that does not register its calls with the platform will talk over one. So the engine asks
before the INVITE, and a refusal is final: `SipError.CallNotPermitted`, no INVITE, and the
dialler says "your phone is on another call".

`LinphoneSipEngineTest` asserts the ordering from inside the fake registry — once both
calls have returned there is nothing left to observe about which went first.

## The path of one incoming call

```
INVITE -> RealLinphoneCoreGateway (mints a call key)
       -> LinphoneSipEngine: snapshot in CallState.Incoming
       -> PlatformCallRegistry.registerIncoming
            refused  -> 486 Busy, nothing shown          (a cellular call is in progress)
            accepted -> SipCallController.incomingCalls
       -> RegistrationService: CallStyle notification + full-screen intent
       -> Ringer: ringtone or vibration, per RingerPolicy
       -> CallActivity -> CallRoute (:feature:calls)
```

`incomingCalls` and `activeCalls` do different jobs here, deliberately. The **event**
starts the foreground service — a call arriving during app startup must not wait for a
state diff to be noticed, which is why that flow is buffered and never replayed. The
**state** decides what the notification shows, so a rebuilt notification cannot re-ring a
call that was already answered.

The push wake path (ADR-004) sits in front of all of this and adds nothing to it: the
message says only "wake up and re-register", the registration comes back, and the INVITE
then arrives on the path above. Caller identity never travels in a push payload (§7).

## Hold, and who may lift it

```
CallScreen hold button  ->  SipEngine.setHold        (ask the FSM, then the stack)
                                 |
                              liblinphone pause()/resume()   -- writes the SDP direction
                                 |
             Paused / PausedByRemote / Resuming / StreamsRunning
                                 |
                          CallStateMapper (given the CURRENT state)
                                 |
                     CallStateMachine  ->  Held(LOCAL | REMOTE | BOTH) / Resuming
                                 |
                          PlatformCallRegistry.onHoldChanged  ->  Telecom
```

**The state moves when the stack accepts the re-INVITE, not when the button is pressed.**
The same rule as answering: a call shown as held whose re-INVITE was refused with a 488 is
a screen lying about where the audio is going. `setHold` therefore asks `CallStateMachine`
whether the action is legal, asks the stack to do it, and stops.

**Four stack states, because three of them mean different things.** `Paused` is our hold,
`PausedByRemote` is theirs, `Resuming` is our re-INVITE in flight, and `StreamsRunning`
means whichever of those has just completed — which is why `CallStateMapper` is given the
call's current state rather than guessing from the event alone. Collapsing our hold and
theirs into one state is how "resume did nothing" bugs happen: only one of them is ours to
lift, and with both ends holding, resuming leaves the call `Held(REMOTE)`.

**The SDP direction is the stack's to write.** `pause()` produces `sendonly` while only we
hold and `inactive` once both ends do. Setting a direction by hand through call parameters
would re-derive a rule liblinphone already applies, and the both-hold case is exactly where
a hand-rolled version gets it wrong.

## Mute is two things, and both are set

The stack's mute stops uplink audio for one call — per call, not core-wide, so a second
call (Task 56) is not silenced by a mute the user never applied to it. The platform's mute
is what the system call UI and a Bluetooth headset's mute button read.

Telecom exposes no public way for a self-managed connection to report its own mute:
`Connection.setMuteState` is package-private and `requestCallEndpointChange` (API 34)
covers routing only. So `TelecomCallRegistry` sets the device's microphone flag, and
releases it when the call ends — a microphone left muted afterwards is a device-wide mute
with nothing on screen to explain it.

The inbound direction already existed: a headset's own mute button arrives through
`onCallAudioStateChanged` and is applied like any other mute. The engine short-circuits a
mute that is already in force, which is both the contract's idempotence and what stops the
app echoing the platform back at itself.

## DTMF

`RFC 4733 telephone-event` by default, `SIP INFO` when Settings says so — read **per
digit**, so a mode changed because an IVR is not hearing the caller applies to the next key
press rather than the next call. Exactly one carrier is enabled on the core before each
digit; with both on, liblinphone sends the digit twice and an IVR that counts keypresses
hears two.

The tone the caller hears is the stack's: liblinphone plays the digit locally as it sends
it. A second tone generated in the app would double every keypress, so the screen's own
feedback is the line of digits above the keypad — which is also the only record of them.
DTMF digits are never logged, because a sequence is a PIN or a card number as often as it
is a menu choice (§7).

A, B, C and D sit behind a disclosure on the keypad. Some PBX and carrier signalling needs
them, no telephone has ever shown them, and `DtmfDigit` carries all sixteen so the path
below the UI cannot quietly drop the four that are rare.

## Where each decision lives, and why

| Decision | Lives in | Why not somewhere else |
|---|---|---|
| Which account places a call, and what `1001` means | `PlaceCallUseCase` (`:domain`) | Every screen that dials must resolve it identically; a copy in a ViewModel drifts. |
| Whether a stack event is a legal transition | `CallStateMachine` (`:domain`) | Pure, exhaustively tested, and the same rules the fake enforces. |
| What a liblinphone call state means | `CallStateMapper` (`:data:sip`) | liblinphone does not run on the JVM, so a mapping inside the gateway could only be exercised on a device. |
| Whether the platform permits a call | Telecom, asked through `PlatformCallRegistry` | Only Telecom knows about the cellular call. Guessing is how a SIP call talks over a phone call. |
| Which notification to show | `CallNotificationPolicy` (`:app`) | A ringing call outranks an ongoing one; that is a rule, and rules belong where a test can reach them. |
| Whether to ring | `RingerPolicy` (`:app`) | Silent mode and Do Not Disturb are the user's decision, and "did we respect it" must be assertable without a handset and a switch. |
| Where audio goes | `AudioRoutePolicy` (`:app`), applied through Telecom | Telecom owns routing and arbitration; two things setting a route fight over the SCO link. |
| Whether a push is worth waking for | `PushWakePolicy` (`:app`) | FCM cannot be driven from a JVM test, so everything decidable without it is decided outside it. |
| Which buttons a call offers | `CallControlAvailability` (`:feature:calls`) | Derived from the phase, so a button cannot be offered for an action the FSM would reject. |
| What a hold event means, given where the call was | `CallStateMapper` (`:data:sip`) | One stack state can mean our hold, their hold, or a resume completing; only the current state disambiguates it. |
| Whether a hold or resume is legal at all | `CallStateMachine` (`:domain`) | `HoldParty` is the reason both ends holding resolves correctly, and it is asserted without a stack. |
| Which carrier a DTMF digit takes | `AppSettings.dtmfMode`, read per digit | §5.1 makes it configurable; reading it at send time is what makes a change apply to the next digit. |

## One notification, not two

`RegistrationService` renders **either** the registration summary **or** the call — a
`CallStyle` notification with answer and decline, or with hang up. One foreground
notification means the `phoneCall` service type is attached to the thing that is actually a
phone call, and the user is never shown "1 active call" beside a ringing card that
disagrees with it.

The call channel is created **silent**: Telecom does not ring for self-managed calls, so
the app owns the ringtone, and a channel sound on top of it would ring twice and keep
ringing after the call was answered.

## Rejecting: 603, not 486

Task 37's done-when says "rejecting sends 486". The implementation sends **603 Decline**
when the user declines, and 486 Busy Here when the device is genuinely busy — which is
when Telecom refuses an inbound call because a cellular call is already up.

That follows §5.2 and the `SipCallController` contract, which map `HangupReason.BUSY` to
486 and `LOCAL_REJECTED` to 603 and say the distinction must reflect what the user actually
chose. It also keeps the call log honest: a declined call is recorded as declined rather
than as busy. The 486 path is implemented and used; it is simply used where it is true.

## What is not built yet

- **Transfer** (Task 55) and **conferencing** (Task 60) still answer `EngineUnavailable`.
  They are delegated to `UnavailableSipEngine` rather than restubbed, so there is one set
  of "not built yet" answers instead of two that can drift.
- **The push gateway** is a backend component and is out of scope for this app (ADR-004,
  §11). The client half — RFC 8599 parameters on REGISTER, token rotation, the wake path —
  is built and unit-tested; it does nothing until a gateway sends to it.
- **`google-services.json`** is deliberately not in this repository. Without it the FCM SDK
  never delivers a message, `PushTokenPublisher` logs that push is unconfigured, and the
  app registers and takes calls exactly as before — it simply misses calls that arrive in
  Doze.

## What still needs a device

Everything below the seams above. In particular: that all four audio routes are audible,
that a headset switches the route mid-call, that the full-screen intent shows on a locked
screen, and that a push wakes a force-stopped app. Each is recorded against its task in
`tasks.md` rather than ticked from a passing unit test.

Hold, mute and DTMF add three more of the same kind, and they need the FreeSWITCH target
rather than only a handset: that a local hold puts `a=sendonly` on the wire and stops media
in that direction, that a far end hears silence while muted, and that an IVR receives all
sixteen tones over telephone-event and over INFO. Everything above the SDK seam — which
event means what, which transition is legal, which carrier is chosen — is asserted on the
JVM, because that is the half a test can reach without a server.
