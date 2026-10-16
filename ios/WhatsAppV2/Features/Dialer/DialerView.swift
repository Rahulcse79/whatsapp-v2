//
//  DialerView.swift
//  The keypad.
//

import SwiftUI
import Shared

struct DialerView: View {
    @EnvironmentObject private var engine: SipEngine
    @EnvironmentObject private var accounts: AccountStore
    @EnvironmentObject private var settings: SettingsStore

    @State private var dialled = ""
    @State private var selectedAccountId: String?

    private var account: SipAccount? {
        if let selectedAccountId, let match = accounts.account(id: selectedAccountId) { return match }
        return accounts.defaultAccount(registrations: engine.registrations)
    }

    private var registration: RegistrationStatus {
        guard let account else { return .unregistered }
        return engine.registrations[account.id] ?? .unregistered
    }

    /// Live validation, from the shared module — the same rule the Android dialler uses,
    /// so a number that dials on one platform dials on the other.
    private var resolvedTarget: String? {
        guard let account, !dialled.isEmpty else { return nil }
        return SharedFacade.shared.resolveDialledTarget(
            input: dialled,
            accountDomain: account.domain,
            accountPort: Int32(account.effectivePort)
        )
    }

    private var canDial: Bool { resolvedTarget != nil && registration.isUsable }

    var body: some View {
        VStack(spacing: 0) {
            accountBar

            Spacer(minLength: 0)

            VStack(spacing: 4) {
                Text(dialled.isEmpty ? " " : dialled)
                    .font(.system(size: 36, weight: .light, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .animation(nil, value: dialled)   // the digits must not slide about

                // The resolved URI, shown while typing. Not decoration: it is how a user
                // sees that `1004` is going to `sip:1004@192.168.7.14:5070` and not to
                // some other port, which is exactly the defect that made the carried
                // port a rule.
                Text(resolvedTarget ?? " ")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .padding(.horizontal)
            .frame(height: 70)

            Spacer(minLength: 0)

            KeypadGrid { key in
                dialled.append(key)
                // DTMF into a live call as well as into the field: a keypad opened
                // during a call is almost always somebody navigating an IVR.
                if let call = engine.focusedCall, call.phase.isEstablished {
                    engine.sendDtmf(key, callId: call.id)
                }
            }
            .padding(.horizontal, 24)

            callRow
                .padding(.vertical, 20)
        }
        .navigationTitle("Keypad")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var accountBar: some View {
        HStack(spacing: 8) {
            Image(systemName: registration.systemImage)
                .foregroundStyle(registration.tint)
                .imageScale(.small)

            if accounts.accounts.count > 1 {
                Picker("Account", selection: Binding(
                    get: { account?.id ?? "" },
                    set: { selectedAccountId = $0 }
                )) {
                    ForEach(accounts.accounts) { candidate in
                        Text(candidate.label).tag(candidate.id)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            } else {
                Text(account?.label ?? "No account")
                    .font(.subheadline)
            }

            Spacer()

            Text(registration.displayName)
                .font(.caption)
                .foregroundStyle(registration.tint)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var callRow: some View {
        HStack(spacing: 28) {
            // Video first and on the left, so the big green audio button stays where a
            // thumb expects it whether or not video is available.
            Button {
                place(video: true)
            } label: {
                Image(systemName: "video.fill")
                    .font(.title3)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(Color.accentColor.opacity(0.15)))
            }
            .disabled(!canDial)
            .accessibilityLabel("Video call")

            Button {
                place(video: settings.current.preferVideoOnOutgoingCalls)
            } label: {
                Image(systemName: "phone.fill")
                    .font(.title)
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(canDial ? Color.green : Color.gray))
            }
            .disabled(!canDial)
            .accessibilityLabel("Call")

            Button {
                if !dialled.isEmpty { dialled.removeLast() }
            } label: {
                Image(systemName: "delete.left")
                    .font(.title3)
                    .frame(width: 52, height: 52)
            }
            .opacity(dialled.isEmpty ? 0 : 1)
            .disabled(dialled.isEmpty)
            .accessibilityLabel("Delete")
        }
    }

    private func place(video: Bool) {
        guard let account else { return }
        engine.placeCall(to: dialled, from: account, video: video)
        dialled = ""
    }
}

/// The 3x4 grid. Separate so the in-call DTMF pad uses the same one and the two cannot
/// drift apart in layout or in which characters they send.
struct KeypadGrid: View {
    let onKey: (String) -> Void

    private let keys: [[(String, String)]] = [
        [("1", ""),    ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"),("8", "TUV"), ("9", "WXYZ")],
        [("*", ""),    ("0", "+"),   ("#", "")],
    ]

    var body: some View {
        VStack(spacing: 12) {
            ForEach(keys.indices, id: \.self) { row in
                HStack(spacing: 24) {
                    ForEach(keys[row], id: \.0) { key, letters in
                        Button { onKey(key) } label: {
                            VStack(spacing: 1) {
                                Text(key).font(.system(size: 30, weight: .regular))
                                if !letters.isEmpty {
                                    Text(letters)
                                        .font(.system(size: 10, weight: .semibold))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 58)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}
