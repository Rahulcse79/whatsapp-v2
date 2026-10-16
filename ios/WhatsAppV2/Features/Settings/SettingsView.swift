//
//  SettingsView.swift
//

import SwiftUI
import Shared

struct SettingsView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var engine: SipEngine
    @EnvironmentObject private var log: CallLogStore
    @EnvironmentObject private var push: PushKitController

    @State private var showingTrace = false
    @State private var showingCodecs = false

    private var frameRates: [Int] {
        SharedFacade.shared.videoFrameRateOptions().map(\.intValue)
    }

    var body: some View {
        Form {
            Section("Calls") {
                Toggle("Start outgoing calls with video", isOn: Binding(
                    get: { settings.current.preferVideoOnOutgoingCalls },
                    set: { value in settings.update { $0.preferVideoOnOutgoingCalls = value } }
                ))
                Picker("Default audio", selection: Binding(
                    get: { settings.current.defaultAudioRoute },
                    set: { value in settings.update { $0.defaultAudioRoute = value } }
                )) {
                    Text("Phone").tag(AppSettings.AudioRouteSetting.earpiece)
                    Text("Speaker").tag(AppSettings.AudioRouteSetting.speaker)
                }
            }

            Section {
                Picker("Frame rate", selection: Binding(
                    get: { settings.current.videoFrameRate },
                    set: { value in settings.update { $0.videoFrameRate = value } }
                )) {
                    ForEach(frameRates, id: \.self) { Text("\($0) fps").tag($0) }
                }
                Stepper("Bitrate: \(settings.current.videoBitrateKbps) kbps",
                        value: Binding(
                            get: { settings.current.videoBitrateKbps },
                            set: { value in settings.update { $0.videoBitrateKbps = value } }
                        ), in: 128...2048, step: 128)
            } header: {
                Text("Video")
            } footer: {
                // Not a disclaimer — a real constraint. pjmedia has no API to
                // reconfigure a running video stream, and an autonomous re-INVITE races
                // the user's own and freezes the media, so the setting lands at the next
                // stream boundary. Saying so stops "I changed it and nothing happened".
                Text("Changes apply to the next call, or to the next time video is turned on during a call.")
            }

            Section("History") {
                Picker("Keep history for", selection: Binding(
                    get: { settings.current.callHistoryRetentionDays },
                    set: { value in
                        settings.update { $0.callHistoryRetentionDays = value }
                        log.setRetention(days: value)
                    }
                )) {
                    Text("7 days").tag(7)
                    Text("30 days").tag(30)
                    Text("90 days").tag(90)
                    Text("Forever").tag(0)
                }
                Button("Clear call history", role: .destructive) { log.clear() }
            }

            Section {
                Toggle("SIP trace", isOn: Binding(
                    get: { settings.current.sipTraceEnabled },
                    set: { value in settings.update { $0.sipTraceEnabled = value } }
                ))
                Button("View SIP trace") { showingTrace = true }
                    .disabled(!settings.current.sipTraceEnabled)
                Button("Codec report") { showingCodecs = true }
            } header: {
                Text("Diagnostics")
            } footer: {
                Text("The trace contains SIP addresses. It is kept in memory only and is cleared when the app closes. Turning it on takes effect the next time the stack starts.")
            }

            Section("About") {
                LabeledContent("Stack", value: engine.stackVersion)
                LabeledContent("App", value: Self.appVersion)
                LabeledContent("Push token", value: push.voipToken.map { String($0.prefix(12)) + "…" }
                                                    ?? "not registered")
                    .font(.caption.monospaced())
                if let error = engine.lastError {
                    LabeledContent("Last error", value: error)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $showingTrace) { TraceView() }
        .sheet(isPresented: $showingCodecs) { CodecReportView() }
    }

    private static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }
}

/// The SIP trace, as a scrolling log.
struct TraceView: View {
    @EnvironmentObject private var engine: SipEngine
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(engine.traceLines.enumerated()), id: \.offset) { index, line in
                            Text(line)
                                .font(.system(size: 10, design: .monospaced))
                                .textSelection(.enabled)
                                .id(index)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(8)
                }
                .onChange(of: engine.traceLines.count) { count in
                    // Follow the tail. Without this the view sits at the top while the
                    // interesting lines — the ones from the call being debugged — scroll
                    // past below the fold.
                    withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) }
                }
            }
            .navigationTitle("SIP trace")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Clear") { engine.clearTrace() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// Which codecs this build actually contains, against which ones the domain names.
///
/// The gap is the honest answer to "why is my preference being ignored": a codec named
/// in `CodecPreferences.DEFAULT` that the binary does not contain is silently skipped
/// when priorities are applied, and nothing else in the app would ever say so.
struct CodecReportView: View {
    @EnvironmentObject private var engine: SipEngine
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List {
                let audit = engine.codecAudit()
                Section {
                    ForEach(audit.filter(\.compiled), id: \.name) { entry in
                        HStack {
                            Text(entry.name)
                            Spacer()
                            Text("priority \(entry.priority)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: { Text("Compiled into this build") }

                let missing = audit.filter { !$0.compiled }
                if !missing.isEmpty {
                    Section {
                        ForEach(missing, id: \.name) { entry in
                            Label(entry.name, systemImage: "xmark.circle")
                                .foregroundStyle(.secondary)
                        }
                    } header: {
                        Text("Known but not compiled")
                    } footer: {
                        Text("These are silently dropped from the SDP offer. Lyra is not yet cross-compiled for iOS.")
                    }
                }
            }
            .navigationTitle("Codecs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
