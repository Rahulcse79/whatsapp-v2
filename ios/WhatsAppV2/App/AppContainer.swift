//
//  AppContainer.swift
//  Dependency wiring.
//
//  Hand-rolled rather than a DI framework. Android uses Hilt because it has 69 injection
//  sites across twenty modules; this app has seven objects and one graph, and a
//  container that can be read top to bottom is worth more here than a generated one.
//
//  ## The cycles, which are real and deliberate
//
//  `SipEngine` ↔ `CallKitController` and `SipEngine` ↔ `PushKitController` both point at
//  each other, because the relationship genuinely runs both ways: the engine reports
//  calls up to CallKit, and CallKit performs the user's actions back down into the
//  engine. Neither can be the other's initialiser argument, so each holds the other
//  `weak` and the container joins them after both exist. The weak side is always the
//  one pointing *up* — the engine's reference to CallKit — so the controller does not
//  keep the engine alive past teardown.
//

import Foundation
import Combine

@MainActor
final class AppContainer: ObservableObject {

    let settings: SettingsStore
    let accounts: AccountStore
    let callLog: CallLogStore
    let audio: AudioSessionController
    let engine: SipEngine
    let callKit: CallKitController
    let push: PushKitController

    init() {
        let settings = SettingsStore()
        let accounts = AccountStore()
        let callLog = CallLogStore(retentionDays: settings.current.callHistoryRetentionDays)
        let audio = AudioSessionController()
        let engine = SipEngine(accountStore: accounts, settings: settings, audio: audio)
        let callKit = CallKitController()
        let push = PushKitController()

        self.settings = settings
        self.accounts = accounts
        self.callLog = callLog
        self.audio = audio
        self.engine = engine
        self.callKit = callKit
        self.push = push

        // The cycles, closed here and nowhere else.
        engine.callKit = callKit
        callKit.engine = engine
        push.engine = engine
        push.callKit = callKit

        // The engine does not know what a call log is; it reports an ended call and this
        // is what writes it. One consumer, so a closure rather than a protocol.
        engine.onCallEnded = { [weak callLog] entry in
            callLog?.record(entry)
        }

        // A settings change has to reach the stack. Without this the frame-rate control
        // is inert until the next launch, which is exactly the defect the Android video
        // quality setting shipped with.
        settings.onChange = { [weak engine, weak callLog] updated in
            engine?.applyMediaSettings()
            callLog?.setRetention(days: updated.callHistoryRetentionDays)
        }
    }

    /// Everything that must happen once, at launch, in order.
    func start() {
        // PushKit first. Registering for the VoIP token is cheap and the token may take
        // a moment to arrive; the stack can start while that is in flight.
        push.start()
        engine.start()
    }

    func stop() {
        engine.stop()
    }
}
