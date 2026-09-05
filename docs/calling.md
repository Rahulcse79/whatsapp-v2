# Calling — how a call actually happens

Tasks 35–40 (§3, §5.2, DoD 7 and 8). What each layer owns, and why the seams are where
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

- **Hold** is offered by the screen only from `Connected`, and pressing it reaches an
  engine that still answers `EngineUnavailable`: the re-INVITE is Task 41. The screen does
  not change when it lands.
- **DTMF** (Task 43), **transfer** (Task 55) and **conferencing** (Task 60) are likewise
  still `EngineUnavailable`.
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
