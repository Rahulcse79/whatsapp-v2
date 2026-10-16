//
//  AudioSessionController.swift
//  AVAudioSession, under CallKit's rules.
//
//  ## The rule that shapes this whole file
//
//  **The app configures the session; CallKit activates it.** An app that calls
//  `setActive(true)` itself for a VoIP call is fighting the system: CallKit activates
//  the session when it has decided the call may have audio — after the user accepted,
//  after any cellular call was put on hold, after Siri finished — and it does so by
//  calling `provider(_:didActivate:)`. Activating early takes the session before the
//  system is ready and the usual symptom is a call that connects with no audio in
//  either direction until it is held and resumed.
//
//  So `setCategory` and `setMode` happen here, up front and repeatedly as the route
//  changes, and `setActive` happens nowhere. The one exception is deactivation on
//  teardown, which CallKit does not do for us.
//
//  ## Why `.voiceChat` and not `.videoChat`
//
//  Both enable the voice-processing I/O unit (hardware echo cancellation and noise
//  suppression), which is why `config_site.h` turns Speex's AEC off. `.videoChat` also
//  prefers the speaker by default, which is wrong for an audio call and wrong for a
//  video call the user started from the earpiece. The route is chosen explicitly below
//  instead, which is the behaviour the Android side had to add after video calls kept
//  starting on the earpiece by a different accident.
//

import AVFoundation
import Combine

@MainActor
final class AudioSessionController: ObservableObject {

    @Published private(set) var currentRoute: AudioRoute = .earpiece
    @Published private(set) var availableRoutes: [AudioRoute] = [.earpiece, .speaker]

    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []

    /// What the user last asked for, so a route change caused by unplugging a headset
    /// returns to it rather than to whatever the system picked.
    private var desiredRoute: AudioRoute = .earpiece

    init() {
        observeRouteChanges()
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    // MARK: - Configuration

    /// Configures the session for a call. Called before reporting the call to CallKit,
    /// never with `setActive`.
    func configureForCall(video: Bool) {
        do {
            try session.setCategory(
                .playAndRecord,
                mode: video ? .videoChat : .voiceChat,
                options: [
                    // Bluetooth hands-free, and A2DP for headphones that only do A2DP.
                    // Without `.allowBluetooth` a car kit is simply not in the route
                    // list and the user cannot choose it.
                    .allowBluetooth,
                    .allowBluetoothA2DP,
                    // The earpiece stays the default. `.defaultToSpeaker` would override
                    // the explicit route choice below on every category change.
                    .duckOthers,
                ]
            )
            // 20 ms. Matches the packet time of G.711 and Opus at their usual settings,
            // so the I/O unit delivers one packet per callback and the stack does no
            // re-buffering. Asking for less costs CPU for latency the network throws
            // away; asking for more adds a frame of delay at every hop.
            try session.setPreferredIOBufferDuration(0.02)
            try session.setPreferredSampleRate(48_000)
        } catch {
            NSLog("[audio] could not configure the session: \(error.localizedDescription)")
        }
        refreshAvailableRoutes()
        applyRoute(desiredRoute)
    }

    /// CallKit activated the session. PJSIP may open the device now, and the route the
    /// user chose has to be re-applied — activation resets the override.
    func sessionWasActivated() {
        refreshAvailableRoutes()
        applyRoute(desiredRoute)
    }

    /// CallKit deactivated it. The session is released so other audio can resume, with
    /// `.notifyOthersOnDeactivation` so a paused music app actually restarts.
    func sessionWasDeactivated() {
        do {
            try session.setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            // Routine rather than exceptional: a second call that is still up keeps the
            // session, and iOS reports that as an error on the first call's teardown.
            NSLog("[audio] deactivation declined: \(error.localizedDescription)")
        }
        desiredRoute = .earpiece
    }

    // MARK: - Routing

    func setRoute(_ route: AudioRoute) {
        desiredRoute = route
        applyRoute(route)
    }

    private func applyRoute(_ route: AudioRoute) {
        do {
            switch route {
            case .speaker:
                try session.overrideOutputAudioPort(.speaker)
            case .earpiece:
                try session.overrideOutputAudioPort(.none)
            case .bluetooth, .wiredHeadset:
                // Neither is an override: they are *inputs* the session selects, and
                // asking for `.none` then picking the port is what actually moves the
                // audio. Overriding to the speaker here would do the opposite.
                try session.overrideOutputAudioPort(.none)
                if let port = preferredInput(for: route) {
                    try session.setPreferredInput(port)
                }
            }
        } catch {
            NSLog("[audio] could not route to \(route.rawValue): \(error.localizedDescription)")
        }
        refreshCurrentRoute()
    }

    private func preferredInput(for route: AudioRoute) -> AVAudioSessionPortDescription? {
        let wanted: Set<AVAudioSession.Port> = route == .bluetooth
            ? [.bluetoothHFP, .bluetoothLE]
            : [.headsetMic, .headphones]
        return session.availableInputs?.first { wanted.contains($0.portType) }
    }

    // MARK: - Observation

    private func observeRouteChanges() {
        let routeChange = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: session, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.refreshAvailableRoutes()
                self.refreshCurrentRoute()

                // A headset being unplugged mid-call must not silently move the call to
                // the speaker in a meeting. iOS's own default is the receiver, which is
                // the quiet choice and the right one.
                let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
                if reason == .oldDeviceUnavailable, self.desiredRoute != .speaker {
                    self.desiredRoute = .earpiece
                    self.applyRoute(.earpiece)
                }
            }
        }

        let interruption = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self else { return }
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
                // On the way out of an interruption the route override is gone, so it is
                // re-applied. Without this a call interrupted by an alarm comes back on
                // the earpiece after the user had chosen the speaker.
                if type == .ended { self.applyRoute(self.desiredRoute) }
            }
        }

        observers = [routeChange, interruption]
    }

    private func refreshCurrentRoute() {
        let outputs = session.currentRoute.outputs
        if outputs.contains(where: { $0.portType == .builtInSpeaker }) {
            currentRoute = .speaker
        } else if outputs.contains(where: {
            $0.portType == .bluetoothA2DP || $0.portType == .bluetoothHFP || $0.portType == .bluetoothLE
        }) {
            currentRoute = .bluetooth
        } else if outputs.contains(where: { $0.portType == .headphones }) {
            currentRoute = .wiredHeadset
        } else {
            currentRoute = .earpiece
        }
    }

    private func refreshAvailableRoutes() {
        var routes: [AudioRoute] = [.earpiece, .speaker]
        let inputs = session.availableInputs ?? []
        if inputs.contains(where: { $0.portType == .bluetoothHFP || $0.portType == .bluetoothLE }) {
            routes.append(.bluetooth)
        }
        if inputs.contains(where: { $0.portType == .headsetMic }) {
            routes.append(.wiredHeadset)
        }
        availableRoutes = routes
    }
}
