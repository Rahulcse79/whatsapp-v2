//
//  PushKitController.swift
//  VoIP wakes.
//
//  ## Why PushKit and not an ordinary notification
//
//  The app cannot hold a SIP registration open indefinitely in the background: iOS
//  suspends it, the socket goes away, and the registrar keeps a binding pointing at a
//  device that will not answer. The Android side solves this with FCM and a dialplan
//  that sends a data push before the INVITE; the iOS equivalent is PushKit, and the
//  server-side half is the same `coralx-push-sender` sitting beside FreeSWITCH —
//  targeting APNs with the VoIP topic rather than FCM.
//
//  ## The contract, which is strict and unforgiving
//
//  On iOS 13 and later, an app that receives a VoIP push and does **not** report a call
//  to CallKit before `didReceiveIncomingPushWith` returns is terminated. Do it a few
//  times and iOS stops delivering VoIP pushes to the app entirely — a failure that
//  persists across launches and looks exactly like "push is broken on this device".
//
//  So the order here is: report to CallKit FIRST, from the push payload alone, and only
//  then wake the SIP stack and wait for the INVITE. `CallKitController.reconcile` turns
//  that placeholder into the real call when the INVITE lands, and its timeout cleans up
//  if none ever does.
//
//  ## The token
//
//  The VoIP token is NOT the APNs token an ordinary notification uses; they are
//  different registrations and a server given one in place of the other silently
//  delivers nothing. It is pushed into every account's Contact parameters — `pn-prid`,
//  `pn-provider`, `pn-param` — because that is where the IMS reads them, a detail the
//  Android side had to discover: the same parameters set anywhere but the Contact are
//  accepted, echoed back and never used.
//

import PushKit
import CallKit
import Combine

@MainActor
final class PushKitController: NSObject, ObservableObject {

    /// The current VoIP token as hex, or nil before registration completes.
    @Published private(set) var voipToken: String?

    weak var engine: SipEngine?
    weak var callKit: CallKitController?

    private let registry = PKPushRegistry(queue: .main)

    /// `apns` for production. The provider name is what the IMS matches on to pick a
    /// push gateway, so it has to agree with the server's configuration exactly; the
    /// Android half of the same deployment sends `fcm`.
    private let provider = "apns"

    func start() {
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
    }

    /// Clears the token server-side. Logout must do this: a binding that still carries a
    /// token wakes a device for a call it can no longer answer, and the caller hears
    /// ringback for a phone that will never ring.
    func clearToken() {
        voipToken = nil
        engine?.updatePushToken(nil, provider: provider)
    }
}

extension PushKitController: PKPushRegistryDelegate {

    nonisolated func pushRegistry(_ registry: PKPushRegistry,
                                  didUpdate credentials: PKPushCredentials,
                                  for type: PKPushType) {
        guard type == .voIP else { return }
        let token = credentials.token.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in
            voipToken = token
            engine?.updatePushToken(token, provider: provider)
        }
    }

    nonisolated func pushRegistry(_ registry: PKPushRegistry,
                                  didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }
        Task { @MainActor in
            voipToken = nil
            engine?.updatePushToken(nil, provider: provider)
        }
    }

    nonisolated func pushRegistry(_ registry: PKPushRegistry,
                                  didReceiveIncomingPushWith payload: PKPushPayload,
                                  for type: PKPushType,
                                  completion: @escaping () -> Void) {
        guard type == .voIP else { completion(); return }

        // The payload is whatever the push sender put in it. These keys match what
        // `coralx-push-sender` emits; anything missing degrades to a call with no
        // caller name rather than to no call at all, because reporting SOMETHING to
        // CallKit is mandatory and reporting nothing gets the process killed.
        let dictionary = payload.dictionaryPayload
        let handle = (dictionary["caller"] as? String)
            ?? (dictionary["from"] as? String)
            ?? "Unknown"
        let hasVideo = (dictionary["video"] as? Bool) ?? false

        Task { @MainActor in
            guard let callKit else { completion(); return }
            let uuid = UUID()

            // FIRST. Before the stack is touched, before anything that can fail or
            // block. Everything else in this method is best-effort; this is not.
            callKit.reportPushedCall(uuid: uuid, handle: handle, hasVideo: hasVideo) {
                completion()
            }

            // Now wake the stack so the INVITE has somewhere to arrive. A re-REGISTER
            // rather than a bare start: the push means the registrar believes it can
            // reach this device, and after a suspension that belief is usually about an
            // address the device no longer has.
            if engine?.isRunning == true {
                engine?.refreshRegistrations()
            } else {
                engine?.start()
            }
        }
    }
}
