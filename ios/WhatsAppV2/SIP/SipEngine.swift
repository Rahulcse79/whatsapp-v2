//
//  SipEngine.swift
//  The app's view of the SIP stack.
//
//  Mirrors `com.whatsappv2.domain.engine.SipEngine` — the port the Android app codes
//  against — with one structural difference that is forced rather than chosen: Android's
//  port is a Kotlin interface with `suspend` functions, and Swift cannot *implement* a
//  Kotlin suspend function (it can call one). So this is a Swift type with the same
//  responsibilities rather than a conformance, and the shared module is used for the
//  decisions inside it rather than for its shape.
//
//  ## Where state lives
//
//  `PjsipBridge` reports events and holds no opinion. This type folds those events into
//  the `@Published` properties the UI observes, and it is `@MainActor`, so there is
//  exactly one place a call's phase is decided and it is the same place SwiftUI reads
//  it from. The bridge already delivers every callback on the main queue, so no hop is
//  needed and none is done.
//
//  ## What it does NOT own
//
//  CallKit. `CallKitController` is the only thing that creates, answers and ends calls
//  as far as the system is concerned, and this type reports *to* it. The ordering
//  matters and is the reverse of what feels natural: a user tapping Answer in the app's
//  own UI must ask CallKit to answer, and CallKit then tells this type to tell PJSIP.
//  Answering PJSIP directly leaves the system call sitting in its ringing state and the
//  audio session is never activated — a call that connects in silence.
//

import Foundation
import Combine
import UIKit
import Shared

@MainActor
final class SipEngine: NSObject, ObservableObject {

    // MARK: Observable state

    /// Live registration, keyed by the app's account id. Never persisted.
    @Published private(set) var registrations: [String: RegistrationStatus] = [:]

    /// Every call the stack currently has, newest last.
    @Published private(set) var calls: [ActiveCall] = []

    /// The call the in-call screen is showing, if any. The first non-held call, or the
    /// first call — so answering a second call moves the screen to it without the view
    /// needing to know the rule.
    var focusedCall: ActiveCall? {
        calls.first(where: { !$0.isOnHold && $0.phase.isActive }) ?? calls.first(where: { $0.phase.isActive })
    }

    @Published private(set) var isRunning = false
    @Published private(set) var lastError: String?

    /// The calls currently mixed together locally. Mirrors `SipConferenceController.mixedCalls`.
    @Published private(set) var mixedCalls: Set<Int> = []

    /// The most recent SIP trace lines, newest last, capped. Only collected while the
    /// trace setting is on — an always-on ring buffer of SIP is a privacy problem as
    /// much as a memory one, since the lines carry the user part of every URI.
    @Published private(set) var traceLines: [String] = []

    // MARK: Collaborators

    private let bridge: PjsipBridge
    private let accountStore: AccountStore
    private let settings: SettingsStore
    private let audio: AudioSessionController

    /// Set by `AppContainer` after both exist — they refer to each other, so neither can
    /// be the other's initialiser argument.
    weak var callKit: CallKitController?

    /// Fires when a call ends, with everything the log needs. A closure rather than a
    /// delegate because there is exactly one consumer and it is `CallLogStore`.
    var onCallEnded: ((CallLogEntry) -> Void)?

    /// Retry bookkeeping per account: how many consecutive failures, and the work item
    /// that will try again. Cancelled on success, on logout and on a new attempt, so a
    /// user who fixes a password does not wait out the old backoff.
    private var retryAttempts: [String: Int] = [:]
    private var retryWork: [String: DispatchWorkItem] = [:]

    private var traceEnabled = false
    private static let maxTraceLines = 500

    // MARK: Observation state the bridge extension writes

    /// The far end offered or withdrew video mid-call, by pjsua call id. The in-call
    /// screen reads it to show the "accept video?" prompt and clears it on an answer.
    @Published var pendingVideoRequests: [Int: Bool] = [:]

    /// pjsua's own render views, by call id. Held rather than copied: the view belongs
    /// to the stack, and `VideoTileView` only ever adds it as a subview.
    var remoteVideoViews: [Int: UIView] = [:]
    var remoteVideoSizes: [Int: CGSize] = [:]

    /// Bumped whenever a video window appears or goes away.
    ///
    /// SwiftUI cannot diff a `[Int: UIView]` — `UIView` is a reference type with no
    /// useful equality — so publishing the dictionary changes nothing on screen. A
    /// counter it CAN diff is what makes the tile re-evaluate, and it is cheaper than
    /// wrapping every view in an `Identifiable` box that exists only to be compared.
    @Published var videoRevision: UInt64 = 0

    /// The last DTMF digit the far end sent, for an IVR-style display.
    @Published var lastInboundDtmf: (callId: Int, digit: String)?

    /// The REFER in flight, if any. `isFinal` false means more NOTIFYs are coming.
    @Published var transferStatus: (callId: Int, statusCode: Int, isFinal: Bool)?

    var traceIsEnabled: Bool { traceEnabled }

    func appendTrace(_ line: String) {
        guard !line.isEmpty else { return }
        traceLines.append(line)
        if traceLines.count > Self.maxTraceLines {
            traceLines.removeFirst(traceLines.count - Self.maxTraceLines)
        }
    }

    func clearTrace() { traceLines.removeAll() }

    /// A registration succeeded, so the next failure starts the backoff from zero again.
    func resetRetry(for accountId: String) {
        retryAttempts[accountId] = 0
        retryWork[accountId]?.cancel()
        retryWork[accountId] = nil
    }

    /// `scheduleRetry` is private to this file; the bridge extension calls through here.
    func scheduleRetryPublic(accountId: String, retryAfterSeconds: Int) {
        scheduleRetry(accountId: accountId, retryAfterSeconds: retryAfterSeconds)
    }

    /// Accepts or declines a mid-call video offer and clears the prompt.
    func respondToVideoRequest(callId: Int, accept: Bool) {
        bridge.respondToVideoRequest(callId, accept: accept)
        pendingVideoRequests[callId] = nil
    }

    init(bridge: PjsipBridge = .shared(),
         accountStore: AccountStore,
         settings: SettingsStore,
         audio: AudioSessionController) {
        self.bridge = bridge
        self.accountStore = accountStore
        self.settings = settings
        self.audio = audio
        super.init()
        bridge.delegate = self
    }

    // MARK: - Lifecycle

    func start() {
        guard !isRunning else { return }

        traceEnabled = settings.current.sipTraceEnabled
        // Level 4 is PJSIP's DEBUG. Above it the stack logs every timer tick, which
        // drowns the SIP messages the trace exists to show; below it the messages
        // themselves are gone. Android's PjsipTrace draws the same line in the same place.
        let logLevel = traceEnabled ? 4 : 1

        // All three transports, always. Which one a call uses is the account's business,
        // and a transport that was never created means an account configured for it
        // fails with a message about the account rather than about the transport.
        let transports: [NSNumber] = [
            NSNumber(value: WAVTransport.UDP.rawValue),
            NSNumber(value: WAVTransport.TCP.rawValue),
            NSNumber(value: WAVTransport.TLS.rawValue),
        ]

        do {
            try bridge.start(transports: transports, logLevel: logLevel)
            isRunning = true
            lastError = nil
            applyMediaSettings()
            restoreRegistrations()
        } catch {
            isRunning = false
            lastError = error.localizedDescription
        }
    }

    func stop() {
        guard isRunning else { return }
        retryWork.values.forEach { $0.cancel() }
        retryWork.removeAll()
        bridge.shutdown()
        isRunning = false
        calls.removeAll()
        registrations.removeAll()
    }

    /// Re-registers every account the user has logged in, at start and after a wake.
    ///
    /// This is `RestoreRegistrationsUseCase` on Android, and it exists for the same
    /// reason: the OS kills the process, a crash takes it, an install replaces it, and
    /// before an explicit persisted intent every one of those left the account offline
    /// until somebody opened the app.
    func restoreRegistrations() {
        for account in accountStore.accounts where account.registrationWanted {
            register(account)
        }
    }

    func applyMediaSettings() {
        let s = settings.current
        bridge.setVideoFrameRate(s.videoFrameRate)
        bridge.setVideoBitrateKbps(s.videoBitrateKbps)
        traceEnabled = s.sipTraceEnabled
    }

    // MARK: - Accounts

    func register(_ account: SipAccount) {
        retryWork[account.id]?.cancel()
        retryWork[account.id] = nil

        let config = WAVAccountConfig()
        config.accountId = account.id
        config.username = account.username
        config.authUsername = account.effectiveAuthUsername
        config.password = accountStore.password(for: account.id) ?? ""
        config.domain = account.domain
        config.displayName = account.displayName.isEmpty ? nil : account.displayName
        config.registrar = account.registrar.isEmpty ? nil : account.registrar
        config.outboundProxy = account.outboundProxy.isEmpty ? nil : account.outboundProxy
        config.port = account.port
        config.transport = account.transport.bridgeValue
        config.registrationExpirySeconds = account.registrationExpirySeconds
        config.srtpPolicy = account.srtpPolicy.bridgeValue
        config.stunServer = account.stunServer.isEmpty ? nil : account.stunServer
        config.iceEnabled = account.iceEnabled
        config.keepaliveIntervalSeconds = account.keepaliveIntervalSeconds

        registrations[account.id] = .registering
        do {
            if registrations.keys.contains(account.id) {
                try bridge.updateAccount(config)
            } else {
                try bridge.addAccount(config)
            }
            bridge.setRegistration(true, forAccount: account.id)
            applyCodecPreferences(for: account)
        } catch {
            registrations[account.id] = .failed(reason: .invalidConfiguration, retryScheduled: false)
            lastError = error.localizedDescription
        }
    }

    func unregister(accountId: String) {
        retryWork[accountId]?.cancel()
        retryWork[accountId] = nil
        retryAttempts[accountId] = 0
        bridge.setRegistration(false, forAccount: accountId)
        registrations[accountId] = .unregistered
    }

    func removeAccount(id: String) {
        unregister(accountId: id)
        bridge.removeAccount(id)
        registrations[id] = nil
    }

    /// Applies the account's codec order to the stack.
    ///
    /// The preference list is the shared module's — `SharedFacade.defaultAudioCodecNames()`
    /// when the account has not been customised — so both platforms offer the same SDP.
    /// Anything not in the list is set to priority 0, which removes it from the offer.
    private func applyCodecPreferences(for account: SipAccount) {
        let audio = account.audioCodecs.isEmpty
            ? SharedFacade.shared.defaultAudioCodecNames()
            : account.audioCodecs
        let video = account.videoCodecs.isEmpty
            ? SharedFacade.shared.defaultVideoCodecNames()
            : account.videoCodecs
        bridge.applyAudioCodecOrder(audio)
        bridge.applyVideoCodecOrder(video)
    }

    /// Codecs this binary actually contains, paired with whether the domain knows them.
    /// The difference is the honest answer to "which of my preferences are being
    /// silently dropped" — `CodecAudit` on Android.
    func codecAudit() -> [(name: String, compiled: Bool, priority: Int)] {
        let compiled = (bridge.enumerateAudioCodecs() + bridge.enumerateVideoCodecs())
        let byName = Dictionary(compiled.map { ($0.name.uppercased(), $0) },
                                uniquingKeysWith: { a, _ in a })
        let known = SharedFacade.shared.allAudioCodecNames() + SharedFacade.shared.allVideoCodecNames()
        return known.map { name in
            let match = byName[name.uppercased()]
            return (name: name, compiled: match != nil, priority: Int(match?.priority ?? 0))
        }
    }

    // MARK: - Calls

    /// Places a call. Goes through CallKit first — see the note at the top of the file.
    @discardableResult
    func placeCall(to destination: String, from account: SipAccount, video: Bool) -> UUID? {
        let resolved = SharedFacade.shared.resolveDialledTarget(
            input: destination,
            accountDomain: account.domain,
            accountPort: Int32(account.effectivePort)
        )
        guard let target = resolved else {
            lastError = "\(destination) is not a number this account can dial"
            return nil
        }
        let uuid = UUID()
        callKit?.startOutgoingCall(uuid: uuid,
                                   handle: SharedFacade.shared.labelForUri(uri: target),
                                   video: video)
        pendingOutgoing[uuid] = (target: target, accountId: account.id, video: video)
        return uuid
    }

    /// Outgoing calls CallKit has accepted but PJSIP has not yet been told about.
    private var pendingOutgoing: [UUID: (target: String, accountId: String, video: Bool)] = [:]

    /// CallKit has allowed the outgoing call. Now, and only now, send the INVITE.
    func performOutgoingCall(uuid: UUID) -> Bool {
        guard let pending = pendingOutgoing.removeValue(forKey: uuid) else { return false }
        var error: NSError?
        let callId = bridge.placeCall(to: pending.target,
                                      fromAccount: pending.accountId,
                                      profile: pending.video ? .audioVideo : .audioOnly,
                                      error: &error)
        if callId < 0 {
            lastError = error?.localizedDescription ?? "The call could not be placed"
            return false
        }
        uuidByCallId[Int(callId)] = uuid
        callIdByUUID[uuid] = Int(callId)
        return true
    }

    // Not `private`: `SipEngine+Bridge.swift` is a different file, and Swift's
    // `private` is file-scoped. `internal` is the narrowest visibility that works for a
    // type split across two files, which this one is on purpose.
    var uuidByCallId: [Int: UUID] = [:]
    var callIdByUUID: [UUID: Int] = [:]

    // MARK: Mutations
    //
    // `calls`, `registrations` and `mixedCalls` are `@Published private(set)`, and
    // Swift's `private` is FILE-scoped — so `SipEngine+Bridge.swift`, which is the same
    // type in a different file, cannot assign to them:
    //
    //     error: cannot assign through subscript: 'calls' setter is inaccessible
    //
    // Relaxing them to `var` would fix the build and lose the guarantee that a view
    // cannot write to the call list. These methods keep the guarantee and are a better
    // shape anyway: there is now one place that knows how a call is added, replaced and
    // removed, instead of five index-juggling sites in the event handlers.

    func updateRegistration(_ status: RegistrationStatus, for accountId: String) {
        registrations[accountId] = status
    }

    /// Inserts the call, or replaces the existing one with the same id.
    func upsertCall(_ call: ActiveCall) {
        if let index = calls.firstIndex(where: { $0.id == call.id }) {
            calls[index] = call
        } else {
            calls.append(call)
        }
    }

    /// Applies `change` to the call with this id, if it is still there. A no-op when it
    /// is not, which is the common case for an event that arrives after a hang-up.
    func mutateCall(id: Int, _ change: (inout ActiveCall) -> Void) {
        guard let index = calls.firstIndex(where: { $0.id == id }) else { return }
        change(&calls[index])
    }

    func removeCall(id: Int) {
        calls.removeAll { $0.id == id }
        mixedCalls.remove(id)
    }

    func removeAllCalls() {
        calls.removeAll()
        mixedCalls.removeAll()
    }

    func registerMapping(callId: Int, uuid: UUID) {
        uuidByCallId[callId] = uuid
        callIdByUUID[uuid] = callId
    }

    func clearMapping(callId: Int) {
        if let uuid = uuidByCallId.removeValue(forKey: callId) {
            callIdByUUID[uuid] = nil
        }
    }

    func answer(uuid: UUID, video: Bool) {
        guard let callId = callIdByUUID[uuid] else { return }
        bridge.answerCall(callId, profile: video ? .audioVideo : .audioOnly)
    }

    func end(uuid: UUID) {
        guard let callId = callIdByUUID[uuid] else { return }
        bridge.hangupCall(callId)
    }

    func setHeld(_ held: Bool, uuid: UUID) {
        guard let callId = callIdByUUID[uuid] else { return }
        bridge.setHold(held, forCall: callId)
    }

    func setMuted(_ muted: Bool, uuid: UUID) {
        guard let callId = callIdByUUID[uuid] else { return }
        bridge.setMuted(muted, forCall: callId)
    }

    func sendDtmf(_ digit: String, callId: Int) { bridge.sendDtmf(digit, forCall: callId) }
    func setVideoEnabled(_ enabled: Bool, callId: Int) { bridge.setVideoEnabled(enabled, forCall: callId) }
    func switchCamera(callId: Int) { bridge.switchCamera(forCall: callId) }
    func transfer(callId: Int, to destination: String) { bridge.transferCall(callId, toDestination: destination) }
    func attendedTransfer(callId: Int, to otherCallId: Int) {
        bridge.attendedTransferCall(callId, toCall: otherCallId)
    }

    /// Mixes every active call into one local conference.
    ///
    /// The mix happens on this device rather than on the server — ADR-009 — because a
    /// server-side conference costs a relay hop and the deployed FreeSWITCH drops video
    /// when it bridges. `acrossTheMix` on Android is this same operation.
    func mergeAllCalls() {
        let ids = calls.filter { $0.phase.isEstablished }.map { NSNumber(value: $0.id) }
        guard ids.count >= 2 else { return }
        if bridge.mixCalls(ids) {
            mixedCalls = Set(ids.map(\.intValue))
            // Resume every held leg: a call that is still on hold is not in the mix, it
            // is just connected to it, and the participant hears nothing.
            for call in calls where call.isOnHold {
                bridge.setHold(false, forCall: call.id)
            }
        } else {
            lastError = "Those calls could not be merged"
        }
    }

    func removeFromMix(callId: Int) {
        bridge.unmixCall(callId)
        mixedCalls.remove(callId)
    }

    func uuid(forCallId callId: Int) -> UUID? { uuidByCallId[callId] }
    func callId(forUUID uuid: UUID) -> Int? { callIdByUUID[uuid] }

    // MARK: - Audio

    /// CallKit activated the session. Tell the stack it may open the device now.
    func audioSessionActivated() {
        audio.sessionWasActivated()
        bridge.activateAudioSession()
    }

    func audioSessionDeactivated() {
        bridge.deactivateAudioSession()
        audio.sessionWasDeactivated()
    }

    func setAudioRoute(_ route: AudioRoute) { audio.setRoute(route) }
    var currentAudioRoute: AudioRoute { audio.currentRoute }
    var availableAudioRoutes: [AudioRoute] { audio.availableRoutes }

    /// Configures the session before an outgoing call is reported to CallKit.
    /// Configuration only — `setActive` is CallKit's, always.
    func configureAudioForOutgoing(video: Bool) {
        audio.configureForCall(video: video)
        // Video calls start on the speaker, audio calls on the earpiece.
        //
        // Stated rather than left to the category, because leaving it to the category is
        // how video calls ended up on the earpiece twice on Android: `.videoChat` does
        // prefer the speaker, but an explicit route set for a previous audio call
        // survives the category change and wins.
        audio.setRoute(video ? .speaker : settings.current.defaultAudioRoute.route)
    }

    /// The same decision for an inbound call, made before CallKit is told about it so
    /// the first moment of audio is already on the right route.
    func setAudioRouteForIncoming(video: Bool) {
        audio.configureForCall(video: video)
        audio.setRoute(video ? .speaker : settings.current.defaultAudioRoute.route)
    }

    /// Everything down, now. Called when CallKit resets: the system believes no call
    /// exists, so a dialog the stack is still holding is one the user can neither see
    /// nor end.
    func hangupEverything() {
        bridge.hangupAllCalls()
        removeAllCalls()
        uuidByCallId.removeAll()
        callIdByUUID.removeAll()
    }

    /// Re-REGISTER every account that wants one, without rebuilding anything.
    func refreshRegistrations() { bridge.refreshAllRegistrations() }

    /// Publishes the PushKit VoIP token into every account's Contact parameters.
    ///
    /// The bundle identifier goes out as `pn-param` because that is what the IMS pairs
    /// with the provider to pick an APNs topic — a VoIP push is addressed to
    /// `<bundle id>.voip`, and a server given the wrong one silently delivers nothing.
    func updatePushToken(_ token: String?, provider: String) {
        bridge.setPushToken(token,
                            provider: provider,
                            bundleId: Bundle.main.bundleIdentifier ?? "")
    }

    /// What PJSIP this binary actually contains, read from the library rather than
    /// from a constant — a build that silently lost a codec or shipped a different
    /// pjproject says so on the Settings screen instead of being believed.
    var stackVersion: String { bridge.stackVersion }

    /// The local camera preview view, started on demand.
    ///
    /// Read through the bridge every time rather than cached: pjsua destroys and
    /// recreates the preview window when the capture device changes, and a cached view
    /// after a camera flip is a view the renderer has stopped drawing into — the
    /// self-view freezes on the last frame from the old camera.
    var localPreview: UIView? { bridge.localPreviewView() }

    func startLocalPreview() { bridge.startLocalPreview() }
    func stopLocalPreview() { bridge.stopLocalPreview() }

    // MARK: - Retry

    /// Schedules the next REGISTER attempt using the shared backoff.
    ///
    /// The delay comes from `RegistrationBackoff` in the shared module — the same
    /// exponential window with jitter that Android uses — so two platforms in the same
    /// building do not retry in lockstep after an outage, and neither hammers a
    /// registrar that has just come back.
    private func scheduleRetry(accountId: String, retryAfterSeconds: Int) {
        let attempt = retryAttempts[accountId] ?? 0
        retryAttempts[accountId] = attempt + 1

        let delay = SharedFacade.shared.registrationRetryDelaySeconds(
            attempt: Int32(attempt),
            retryAfterSeconds: Int32(retryAfterSeconds),
            seed: 0
        )

        retryWork[accountId]?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            Task { @MainActor in
                guard let account = self.accountStore.account(id: accountId),
                      account.registrationWanted else { return }
                self.bridge.setRegistration(true, forAccount: accountId)
            }
        }
        retryWork[accountId] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }
}
