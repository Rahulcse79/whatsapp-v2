//
//  CallKitController.swift
//  The system's view of our calls.
//
//  ## Why every call goes through CallKit, including ones the user started in our own UI
//
//  CallKit is not a notification style. It is how iOS knows a call exists, and it is the
//  only thing that can:
//
//    * put the call on the lock screen and the Dynamic Island;
//    * hold it when a cellular call arrives, instead of letting the two talk over each
//      other — the same requirement Android's `ConnectionService` meets, and the reason
//      hand-rolled call notifications are a rejected design on both platforms;
//    * activate the audio session at the right moment (see `AudioSessionController`);
//    * keep the app alive in the background while a call is up.
//
//  So the direction of control is the opposite of what feels natural: tapping Answer in
//  our own UI asks CallKit to answer, CallKit grants it, and only then is PJSIP told.
//  Answering PJSIP directly leaves the system call ringing for ever and no audio session
//  is ever activated.
//
//  ## The PushKit contract this file half-implements
//
//  iOS **terminates the app** if it takes a PushKit VoIP wake and does not report a call
//  to CallKit almost immediately — and after a few such kills it stops delivering VoIP
//  pushes to the app at all. `PushKitController` therefore reports the call from the
//  push payload *before* the SIP INVITE has arrived, and `reconcile(uuid:with:)` below
//  is how the placeholder becomes the real call when the INVITE lands.
//

import CallKit
import AVFoundation
import Combine

@MainActor
final class CallKitController: NSObject, ObservableObject {

    private let provider: CXProvider
    private let callController = CXCallController()

    /// Set by `AppContainer`; they refer to each other.
    weak var engine: SipEngine?

    /// Calls reported to CallKit from a push, before any INVITE exists for them.
    /// Keyed by the UUID the push carried, which the INVITE's Call-ID is matched to.
    private var placeholders: [UUID: String] = [:]

    /// A placeholder that no INVITE ever arrived for has to be ended, or the system call
    /// hangs on screen until the user force-quits. Keyed by UUID.
    private var placeholderTimeouts: [UUID: DispatchWorkItem] = [:]

    /// How long to wait for an INVITE after a push wake.
    ///
    /// Ten seconds. The push and the INVITE take the same path to the same device, so
    /// the gap is a round trip and a registration refresh, not a human delay. Longer and
    /// a push for a call the caller already abandoned leaves a phantom on the lock
    /// screen; shorter and a slow mobile network kills a real call.
    private static let placeholderTimeout: TimeInterval = 10

    override init() {
        let configuration: CXProviderConfiguration
        if #available(iOS 14.0, *) {
            configuration = CXProviderConfiguration()
        } else {
            configuration = CXProviderConfiguration(localizedName: "Coral")
        }
        configuration.supportsVideo = true
        configuration.maximumCallGroups = 1
        // 8, matching PJSUA_MAX_CALLS and the local conference ceiling. CallKit refuses
        // to report a call beyond this, so a number smaller than the stack's would make
        // the eighth conference leg impossible for a reason nothing in the SIP log shows.
        configuration.maximumCallsPerCallGroup = 8
        // Generic, not phoneNumber: these are SIP addresses, and a handle typed as a
        // phone number is reformatted by the system — `1001` becomes a phone number in
        // the recents list and dialling it back leaves the app.
        configuration.supportedHandleTypes = [.generic, .phoneNumber]
        configuration.iconTemplateImageData = UIImage(named: "CallKitIcon")?.pngData()

        provider = CXProvider(configuration: configuration)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    // MARK: - Reporting into CallKit

    /// An INVITE arrived while the app was running.
    func reportIncomingCall(uuid: UUID, handle: String, hasVideo: Bool) {
        cancelPlaceholderTimeout(uuid)

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = handle
        update.hasVideo = hasVideo
        update.supportsHolding = true
        update.supportsGrouping = true
        update.supportsUngrouping = true
        update.supportsDTMF = true

        engine?.setAudioRouteForIncoming(video: hasVideo)

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error {
                // The usual cause is Do Not Disturb or a blocked caller, and iOS expects
                // the app to end the call rather than leave it half-reported.
                NSLog("[callkit] incoming call refused: \(error.localizedDescription)")
                Task { @MainActor in self.engine?.end(uuid: uuid) }
            }
        }
    }

    /// A PushKit wake. Reports a call before any INVITE exists — this is what keeps iOS
    /// from killing the process — and starts the timeout that cleans it up if no INVITE
    /// follows.
    func reportPushedCall(uuid: UUID, handle: String, hasVideo: Bool, completion: @escaping () -> Void) {
        placeholders[uuid] = handle

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = handle
        update.hasVideo = hasVideo
        update.supportsHolding = true
        update.supportsDTMF = true

        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            if let error {
                NSLog("[callkit] pushed call refused: \(error.localizedDescription)")
            }
            completion()
            Task { @MainActor in self?.startPlaceholderTimeout(uuid) }
        }
    }

    /// The INVITE for a pushed call arrived. Replaces the placeholder's details with the
    /// real ones and adopts the UUID, so there is one system call rather than two.
    func reconcile(placeholder uuid: UUID, withCallId callId: Int, handle: String, hasVideo: Bool) {
        cancelPlaceholderTimeout(uuid)
        placeholders[uuid] = nil
        engine?.registerMapping(callId: callId, uuid: uuid)

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = handle
        update.hasVideo = hasVideo
        provider.reportCall(with: uuid, updated: update)
    }

    func reportOutgoingCallRinging(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, startedConnectingAt: nil)
    }

    func reportOutgoingCallConnected(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    func reportCallEnded(uuid: UUID, reason: HangupReason) {
        cancelPlaceholderTimeout(uuid)
        placeholders[uuid] = nil
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason.callKitReason)
    }

    // MARK: - Asking CallKit to act

    /// The user tapped Call in our UI. CallKit decides whether it may happen.
    func startOutgoingCall(uuid: UUID, handle: String, video: Bool) {
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .generic, value: handle))
        action.isVideo = video
        request(CXTransaction(action: action), describing: "start")
    }

    func requestEnd(uuid: UUID) {
        request(CXTransaction(action: CXEndCallAction(call: uuid)), describing: "end")
    }

    func requestHold(_ onHold: Bool, uuid: UUID) {
        let action = CXSetHeldCallAction(call: uuid, onHold: onHold)
        request(CXTransaction(action: action), describing: "hold")
    }

    func requestMute(_ muted: Bool, uuid: UUID) {
        let action = CXSetMutedCallAction(call: uuid, muted: muted)
        request(CXTransaction(action: action), describing: "mute")
    }

    func requestAnswer(uuid: UUID) {
        request(CXTransaction(action: CXAnswerCallAction(call: uuid)), describing: "answer")
    }

    private func request(_ transaction: CXTransaction, describing what: String) {
        callController.request(transaction) { error in
            if let error {
                NSLog("[callkit] \(what) was refused: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Placeholder housekeeping

    private func startPlaceholderTimeout(_ uuid: UUID) {
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self, self.placeholders[uuid] != nil else { return }
                NSLog("[callkit] no INVITE followed the push for \(uuid); ending the placeholder")
                self.placeholders[uuid] = nil
                self.provider.reportCall(with: uuid, endedAt: Date(), reason: .unanswered)
            }
        }
        placeholderTimeouts[uuid] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.placeholderTimeout, execute: work)
    }

    private func cancelPlaceholderTimeout(_ uuid: UUID) {
        placeholderTimeouts[uuid]?.cancel()
        placeholderTimeouts[uuid] = nil
    }

    /// The UUID of a placeholder waiting for an INVITE, if there is exactly one.
    ///
    /// Exactly one, deliberately: with two pushes in flight there is no way to tell from
    /// an INVITE which placeholder it belongs to — the push payload carries no Call-ID
    /// that the IMS guarantees to echo — and adopting the wrong one puts the second
    /// caller's name on the first caller's call. With two outstanding, the INVITE is
    /// reported as a new call and the stale placeholder times out.
    func soleWaitingPlaceholder() -> UUID? {
        placeholders.count == 1 ? placeholders.keys.first : nil
    }
}

// MARK: - CXProviderDelegate

extension CallKitController: CXProviderDelegate {

    nonisolated func providerDidReset(_ provider: CXProvider) {
        // The system tore everything down — a crash in callservicesd, or the user
        // resetting call state. Every call is gone as far as iOS is concerned, so the
        // stack must agree rather than keep dialogs the user cannot see or end.
        Task { @MainActor in engine?.hangupEverything() }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        Task { @MainActor in
            engine?.configureAudioForOutgoing(video: action.isVideo)
            if engine?.performOutgoingCall(uuid: action.callUUID) == true {
                action.fulfill()
                provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
            } else {
                action.fail()
            }
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor in
            guard let engine else { action.fail(); return }
            let wantsVideo = engine.calls.first { $0.callKitUUID == action.callUUID }?.hasVideo ?? false
            engine.answer(uuid: action.callUUID, video: wantsVideo)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor in
            engine?.end(uuid: action.callUUID)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        Task { @MainActor in
            engine?.setHeld(action.isOnHold, uuid: action.callUUID)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        Task { @MainActor in
            engine?.setMuted(action.isMuted, uuid: action.callUUID)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        Task { @MainActor in
            if let callId = engine?.callId(forUUID: action.callUUID) {
                engine?.sendDtmf(action.digits, callId: callId)
            }
            action.fulfill()
        }
    }

    /// The moment PJSIP is allowed to open the audio device. Not before — see
    /// `AudioSessionController`.
    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        Task { @MainActor in engine?.audioSessionActivated() }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        Task { @MainActor in engine?.audioSessionDeactivated() }
    }
}

private extension HangupReason {
    /// CallKit's own vocabulary. The mapping matters for what the Phone app's recents
    /// list shows: `.remoteEnded` is a completed call, `.unanswered` is a missed one.
    var callKitReason: CXCallEndedReason {
        switch self {
        case .localHangup, .remoteHangup, .cancelled: return .remoteEnded
        case .busy:                                   return .unanswered
        case .noAnswer:                               return .unanswered
        case .rejected:                               return .declinedElsewhere
        case .notFound, .serverError:                 return .failed
        case .networkError:                           return .failed
        }
    }
}
