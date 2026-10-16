//
//  SipEngine+Bridge.swift
//  Folding PJSIP's events into the app's state.
//
//  Split from SipEngine.swift because the two halves answer different questions — that
//  file is "what can the app ask the stack to do", this one is "what does the stack say
//  happened" — and because every method here runs as a result of something on the
//  network rather than something the user did, which is a useful thing to be able to
//  read in one place.
//
//  Every callback arrives on the main queue; `PjsipBridge` guarantees it.
//

import Foundation
import UIKit
import Shared

// The methods below are ordinary `@MainActor` members, inherited from the class.
//
// `PjsipBridgeDelegate` is an Objective-C protocol with no isolation and `SipEngine` is
// `@MainActor`, so something has to reconcile the two. Three ways were considered:
//
//   * `MainActor.assumeIsolated` in each method — the precise tool, and iOS 17. This
//     app deploys to iOS 15.
//   * `extension SipEngine: @preconcurrency PjsipBridgeDelegate` — also precise, and
//     Swift 5.10. It compiles on the CI toolchain and not on the Swift 5.7 that the
//     development machine's Command Line Tools carry, which would cost the only
//     compiler check available there (`swiftc -parse`).
//   * Plain isolated members under Swift 5 language mode with minimal concurrency
//     checking, which is what this is. The compiler warns and accepts.
//
// What makes it SOUND rather than merely accepted is the guarantee on the other side:
// `PjsipBridge` dispatches every delegate call to the main queue. That is stated at
// the top of PjsipBridge.h and implemented in every callback in PjsipBridge.mm, so
// these methods genuinely do run on the main actor.
extension SipEngine: PjsipBridgeDelegate {

    // MARK: - Registration

    func pjsipRegistrationDidChange(_ accountId: String,
                                                state: WAVRegistrationState,
                                                failure: WAVRegistrationFailure,
                                                grantedExpirySeconds: Int,
                                                statusCode: Int) {
        let status: RegistrationStatus
        switch state {
        case .unregistered:
            status = .unregistered
        case .registering:
            status = .registering
        case .registered:
            // The expiry the SERVER granted. Everything timed off registration is
            // timed off this and never off what was asked for: a client that
            // refreshes on its own requested value against a registrar that halved
            // it spends half of every binding unregistered.
            status = .registered(grantedExpirySeconds: grantedExpirySeconds)
            resetRetry(for: accountId)
        case .failed:
            let reason = failure.appValue
            // A failure the user must fix is not retried. Hammering a registrar with
            // a password it has already rejected is how an account gets locked out,
            // and no number of retries will make 401 into 200.
            let willRetry = !reason.requiresUserAction
            status = .failed(reason: reason, retryScheduled: willRetry)
            if willRetry {
                scheduleRetryPublic(accountId: accountId,
                                    retryAfterSeconds: statusCode == 503 ? 0 : 0)
            }
        @unknown default:
            status = .unregistered
        }
        registrations[accountId] = status
    }

    // MARK: - Calls

    func pjsipDidReceiveIncomingCall(_ call: WAVCallInfo) {
        let uuid = UUID()
        registerMapping(callId: call.callId, uuid: uuid)

        let active = makeActiveCall(from: call, uuid: uuid)
        upsert(active)

        // CallKit, immediately. iOS terminates an app that takes a PushKit VoIP wake
        // and does not report a call almost at once, and even without a wake this is
        // what puts the call on the lock screen and stops it being talked over by a
        // cellular call.
        callKit?.reportIncomingCall(uuid: uuid,
                                    handle: active.remoteLabel,
                                    hasVideo: call.hasVideo)
    }

    func pjsipCallDidChange(_ call: WAVCallInfo) {
        let uuid = uuid(forCallId: call.callId) ?? {
            let fresh = UUID()
            registerMapping(callId: call.callId, uuid: fresh)
            return fresh
        }()
        let active = makeActiveCall(from: call, uuid: uuid)
        upsert(active)

        if case .connected = active.phase {
            callKit?.reportOutgoingCallConnected(uuid: uuid)
        } else if case .ringing = active.phase {
            callKit?.reportOutgoingCallRinging(uuid: uuid)
        }
    }

    func pjsipCallDidEnd(_ call: WAVCallInfo) {
        let uuid = uuid(forCallId: call.callId)
        let existing = calls.first { $0.id == call.callId }

        // The log is written from the FINAL info the bridge handed over, not from a
        // lookup: pjsua reuses call ids, and a lookup racing the next INVITE writes
        // the wrong row.
        let startedAt = existing?.connectedAt ?? Date()
        let duration = existing?.connectedAt.map { Int(Date().timeIntervalSince($0)) } ?? 0
        let entry = CallLogEntry(
            id: UUID(),
            accountId: call.accountId,
            remoteUri: call.remoteUri,
            remoteLabel: SharedFacade.shared.labelForUri(uri: call.remoteUri),
            direction: call.isIncoming ? .incoming : .outgoing,
            startedAt: startedAt,
            durationSeconds: duration,
            hadVideo: existing?.hasVideo ?? call.hasVideo,
            reason: call.hangupReason.appValue
        )
        onCallEnded?(entry)

        calls.removeAll { $0.id == call.callId }
        mixedCalls.remove(call.callId)
        clearMapping(callId: call.callId)

        if let uuid {
            callKit?.reportCallEnded(uuid: uuid, reason: entry.reason)
        }
    }

    func pjsipCallMediaDidChange(_ call: WAVCallInfo,
                                             audioLive: Bool,
                                             videoLive: Bool) {
        guard let index = calls.firstIndex(where: { $0.id == call.callId }) else { return }
        calls[index].audioLive = audioLive
        calls[index].videoLive = videoLive
        calls[index].hasVideo = call.hasVideo
        calls[index].isMuted = call.isMuted
        calls[index].phase = phase(from: call)
    }

    func pjsipCallDidReceiveVideoRequest(_ callId: Int, wantsVideo: Bool) {
        pendingVideoRequests[callId] = wantsVideo
    }

    func pjsipVideoWindowDidChange(_ callId: Int,
                                               windowId: Int,
                                               view: UIView?,
                                               size: CGSize) {
        if let view, windowId >= 0 {
            remoteVideoViews[callId] = view
            remoteVideoSizes[callId] = size
        } else {
            remoteVideoViews[callId] = nil
            remoteVideoSizes[callId] = nil
        }
        // A published change the video tile observes. Assigning the dictionary
        // wholesale rather than mutating is what makes SwiftUI see it.
        videoRevision &+= 1
    }

    func pjsipDidReceiveDtmf(_ callId: Int, digit: String) {
        lastInboundDtmf = (callId, digit)
    }

    func pjsipTransferDidProgress(_ callId: Int, statusCode: Int, final isFinal: Bool) {
        transferStatus = (callId: callId, statusCode: statusCode, isFinal: isFinal)
        // A transfer that failed must return the call rather than strand it. PJSIP
        // keeps the dialog up on a failure, so there is nothing to re-establish —
        // but the UI has to stop saying "Transferring".
        if isFinal, statusCode >= 300, let index = calls.firstIndex(where: { $0.id == callId }) {
            calls[index].phase = .connected
        }
    }

    func pjsipDidLog(_ message: String, level: Int) {
        guard traceIsEnabled else { return }
        appendTrace(message.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    // MARK: - Shaping

    private func makeActiveCall(from call: WAVCallInfo, uuid: UUID) -> ActiveCall {
        let existing = calls.first { $0.id == call.callId }
        return ActiveCall(
            id: call.callId,
            accountId: call.accountId,
            remoteUri: call.remoteUri,
            remoteLabel: SharedFacade.shared.labelForUri(uri: call.remoteUri),
            phase: phase(from: call),
            isIncoming: call.isIncoming,
            isMuted: call.isMuted,
            hasVideo: call.hasVideo,
            videoLive: existing?.videoLive ?? false,
            audioLive: existing?.audioLive ?? false,
            connectedAt: call.connectedAt > 0
                ? Date(timeIntervalSince1970: call.connectedAt)
                : existing?.connectedAt,
            callKitUUID: uuid
        )
    }

    /// Hold wins over the INVITE state.
    ///
    /// pjsua reports a held call as CONFIRMED — the dialog really is confirmed — and the
    /// hold lives in the media status. Reading only the INVITE state is what makes a
    /// held call look connected, which is the same conflation `CallState.Held` exists to
    /// prevent on Android.
    private func phase(from call: WAVCallInfo) -> CallPhase {
        if call.heldBy != WAVHoldParty.none, call.state == .connected {
            switch call.heldBy {
            case .local:  return .held(by: .local)
            case .remote: return .held(by: .remote)
            case .both:   return .held(by: .both)
            case WAVHoldParty.none: break
            @unknown default: break
            }
        }
        switch call.state {
        case .idle:        return .idle
        case .calling:     return .calling
        case .ringing:     return .ringing
        case .earlyMedia:  return .earlyMedia
        case .incoming:    return .incoming
        case .connecting:  return .connecting
        case .connected:   return .connected
        case .terminated:
            return .terminated(reason: call.hangupReason.appValue,
                               statusCode: call.lastStatusCode > 0 ? call.lastStatusCode : nil)
        @unknown default:  return .idle
        }
    }

    private func upsert(_ call: ActiveCall) {
        if let index = calls.firstIndex(where: { $0.id == call.id }) {
            calls[index] = call
        } else {
            calls.append(call)
        }
    }
}

// MARK: - Enum translation
//
// One place, so a value added to the bridge's enum produces a compiler error here rather
// than a silently wrong default somewhere in a view.

extension WAVRegistrationFailure {
    var appValue: RegistrationFailureReason {
        switch self {
        case .authenticationFailed: return .authenticationFailed
        case .accountRejected:      return .accountRejected
        case .networkUnavailable:   return .networkUnavailable
        case .timeout:              return .timeout
        case .serverUnavailable:    return .serverUnavailable
        case .transportFailure:     return .transportFailure
        case .invalidConfiguration: return .invalidConfiguration
        case .none:                 return .transportFailure
        @unknown default:           return .transportFailure
        }
    }
}

extension WAVHangupReason {
    var appValue: HangupReason {
        switch self {
        case .localHangup:  return .localHangup
        case .remoteHangup: return .remoteHangup
        case .rejected:     return .rejected
        case .busy:         return .busy
        case .notFound:     return .notFound
        case .noAnswer:     return .noAnswer
        case .serverError:  return .serverError
        case .networkError: return .networkError
        case .cancelled:    return .cancelled
        @unknown default:   return .remoteHangup
        }
    }
}

extension SipTransport {
    var bridgeValue: WAVTransport {
        switch self {
        case .udp: return .UDP
        case .tcp: return .TCP
        case .tls: return .TLS
        }
    }
}

extension SrtpPolicy {
    var bridgeValue: WAVSrtpPolicy {
        switch self {
        case .disabled:  return .disabled
        case .optional:  return .optional
        case .mandatory: return .mandatory
        }
    }
}
