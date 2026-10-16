//
//  CallView.swift
//  The in-call screen: audio, video, conference.
//
//  ## Why the buttons go through CallKit
//
//  Hold and mute are CallKit *actions*, not direct calls into PJSIP. The system keeps
//  its own view of a call's hold and mute state — it is what the lock screen and the
//  Dynamic Island show, and what a Bluetooth headset's buttons drive — so a mute applied
//  only to the stack leaves the two disagreeing, visibly, until something re-syncs them.
//  `CallKitController` performs the action and the stack is told from there.
//

import SwiftUI

struct CallView: View {
    @EnvironmentObject private var engine: SipEngine
    @EnvironmentObject private var callKit: CallKitController

    /// Redraws the timer. One timer for the screen rather than one per call, so a
    /// four-way conference does not run four.
    @State private var now = Date()
    @State private var showingKeypad = false
    @State private var showingTransfer = false
    @State private var transferTarget = ""

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var call: ActiveCall? { engine.focusedCall }
    private var others: [ActiveCall] { engine.calls.filter { $0.id != call?.id && $0.phase.isActive } }

    var body: some View {
        ZStack {
            background

            VStack(spacing: 0) {
                header
                Spacer(minLength: 0)
                if let call, call.hasVideo { videoArea(for: call) } else { avatarArea }
                Spacer(minLength: 0)
                if !others.isEmpty { otherCallsStrip }
                controls
            }
            .padding(.bottom, 24)
        }
        .onReceive(tick) { now = $0 }
        .sheet(isPresented: $showingKeypad) { keypadSheet }
        .sheet(isPresented: $showingTransfer) { transferSheet }
    }

    // MARK: - Pieces

    private var background: some View {
        // Over video the chrome needs its own ground: a translucent material over a
        // dark, moving picture leaves white text on near-white frames unreadable, which
        // is a defect the Android video screen had to fix separately.
        Group {
            if call?.hasVideo == true {
                Color.black
            } else {
                LinearGradient(colors: [Color(.systemIndigo).opacity(0.85), Color(.systemBackground)],
                               startPoint: .top, endPoint: .bottom)
            }
        }
        .ignoresSafeArea()
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text(call?.remoteLabel ?? "No call")
                .font(.title2.weight(.semibold))
                .lineLimit(1)
                .foregroundStyle(call?.hasVideo == true ? .white : .primary)

            Text(statusLine)
                .font(.subheadline)
                .foregroundStyle(call?.hasVideo == true ? .white.opacity(0.8) : .secondary)
                .monospacedDigit()

            // "Connected" with no media is a real and common state — a codec mismatch, a
            // one-way NAT, a held leg nobody resumed. Saying so is the difference
            // between a user who waits and a user who hangs up and redials for ever.
            if let call, call.phase == .connected, !call.audioLive {
                Label("No audio yet", systemImage: "waveform.slash")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.top, 40)
    }

    private var statusLine: String {
        guard let call else { return "" }
        switch call.phase {
        case .calling:     return "Calling…"
        case .ringing:     return "Ringing…"
        case .earlyMedia:  return "Connecting…"
        case .incoming:    return "Incoming call"
        case .connecting:  return "Connecting…"
        case .connected:   return call.duration(at: now).map(Format.duration) ?? "Connected"
        case .held(let by):
            switch by {
            case .local:  return "On hold"
            case .remote: return "Held by the other party"
            case .both:   return "Both on hold"
            }
        case .terminated:  return "Ended"
        case .idle:        return ""
        }
    }

    private var avatarArea: some View {
        Circle()
            .fill(.ultraThinMaterial)
            .frame(width: 128, height: 128)
            .overlay(
                Text(initials)
                    .font(.system(size: 44, weight: .medium))
                    .foregroundStyle(.secondary)
            )
    }

    private var initials: String {
        let label = call?.remoteLabel ?? "?"
        // A SIP extension is usually digits, and the first two of `1001` say more than
        // an initial would.
        return String(label.prefix(2)).uppercased()
    }

    private func videoArea(for call: ActiveCall) -> some View {
        ZStack(alignment: .topTrailing) {
            RemoteVideoTile(engine: engine, callId: call.id)
                .aspectRatio(3.0 / 4.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

            LocalPreviewTile(previewView: engine.localPreview)
                .frame(width: 96, height: 128)
                .padding(10)
        }
        .padding(.horizontal)
    }

    private var otherCallsStrip: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Other calls").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                // Merging is only meaningful with two established calls, and the mix is
                // local — ADR-009 — rather than a REFER into a server conference.
                if engine.calls.filter({ $0.phase.isEstablished }).count >= 2 {
                    Button("Merge") { engine.mergeAllCalls() }
                        .font(.caption.weight(.semibold))
                }
            }
            ForEach(others) { other in
                HStack {
                    Text(other.remoteLabel).font(.subheadline).lineLimit(1)
                    if engine.mixedCalls.contains(other.id) {
                        Image(systemName: "person.2.fill").imageScale(.small).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        callKit.requestEnd(uuid: other.callKitUUID)
                    } label: {
                        Image(systemName: "phone.down.fill").foregroundStyle(.red)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 12)
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 22) {
            if let call {
                HStack(spacing: 28) {
                    circleButton("mic.slash.fill", on: call.isMuted, label: "Mute") {
                        callKit.requestMute(!call.isMuted, uuid: call.callKitUUID)
                    }
                    circleButton("pause.fill", on: call.isOnHold, label: "Hold") {
                        callKit.requestHold(!call.isOnHold, uuid: call.callKitUUID)
                    }
                    circleButton("circle.grid.3x3.fill", on: false, label: "Keypad") {
                        showingKeypad = true
                    }
                }

                HStack(spacing: 28) {
                    routeButton
                    circleButton(call.hasVideo ? "video.fill" : "video.slash.fill",
                                 on: call.hasVideo, label: "Video") {
                        engine.setVideoEnabled(!call.hasVideo, callId: call.id)
                    }
                    circleButton("arrow.uturn.right", on: false, label: "Transfer") {
                        showingTransfer = true
                    }
                }

                if call.hasVideo {
                    Button {
                        engine.switchCamera(callId: call.id)
                    } label: {
                        Label("Flip camera", systemImage: "arrow.triangle.2.circlepath.camera")
                            .font(.footnote)
                    }
                    .tint(.white)
                }
            }

            Button {
                if let call { callKit.requestEnd(uuid: call.callKitUUID) }
            } label: {
                Image(systemName: "phone.down.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(Color.red))
            }
            .accessibilityLabel("End call")
        }
    }

    private var routeButton: some View {
        Menu {
            ForEach(engine.availableAudioRoutes) { route in
                Button {
                    engine.setAudioRoute(route)
                } label: {
                    Label(route.displayName, systemImage: route.systemImage)
                }
            }
        } label: {
            controlLabel(engine.currentAudioRoute.systemImage,
                         on: engine.currentAudioRoute == .speaker,
                         label: "Audio")
        }
    }

    private func circleButton(_ systemImage: String, on: Bool, label: String,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) { controlLabel(systemImage, on: on, label: label) }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
    }

    private func controlLabel(_ systemImage: String, on: Bool, label: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(on ? Color.black : (call?.hasVideo == true ? .white : .primary))
                .frame(width: 58, height: 58)
                .background(Circle().fill(on ? Color.white : Color.primary.opacity(0.12)))
            Text(label)
                .font(.caption2)
                .foregroundStyle(call?.hasVideo == true ? .white.opacity(0.85) : .secondary)
        }
    }

    // MARK: - Sheets

    private var keypadSheet: some View {
        VStack(spacing: 20) {
            Text("Keypad").font(.headline).padding(.top, 20)
            KeypadGrid { key in
                if let call { engine.sendDtmf(key, callId: call.id) }
            }
            .padding(.horizontal, 24)
            Button("Done") { showingKeypad = false }.padding(.bottom, 20)
        }
        .presentationDetents([.medium])
    }

    private var transferSheet: some View {
        NavigationView {
            Form {
                Section("Blind transfer") {
                    TextField("Number or SIP address", text: $transferTarget)
                        .keyboardType(.phonePad)
                        .autocorrectionDisabled()
                    Button("Transfer") {
                        if let call { engine.transfer(callId: call.id, to: transferTarget) }
                        transferTarget = ""
                        showingTransfer = false
                    }
                    .disabled(transferTarget.isEmpty)
                }

                // An attended transfer starts from a call that is already up: the user
                // consults the third party first and then connects the two. Offering it
                // only when a second call exists is what makes that order obvious.
                if !others.isEmpty {
                    Section("Attended transfer") {
                        ForEach(others) { other in
                            Button("Connect to \(other.remoteLabel)") {
                                if let call {
                                    engine.attendedTransfer(callId: call.id, to: other.id)
                                }
                                showingTransfer = false
                            }
                        }
                    }
                }
            }
            .navigationTitle("Transfer")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
