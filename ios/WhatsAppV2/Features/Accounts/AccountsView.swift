//
//  AccountsView.swift
//  The account list and the editor.
//
//  Validation is the shared module's: `SharedFacade.validateAccount` wraps
//  `AccountValidator`, so an account this editor accepts is one the Android editor
//  accepts, down to the port range and the transport/port coherence warnings.
//

import SwiftUI
import Shared

struct AccountsView: View {
    @EnvironmentObject private var accounts: AccountStore
    @EnvironmentObject private var engine: SipEngine

    @State private var editing: SipAccount?
    @State private var creating = false

    var body: some View {
        List {
            if accounts.accounts.isEmpty {
                Text("No accounts yet. Add one to start making calls.")
                    .foregroundStyle(.secondary)
            }
            ForEach(accounts.accounts) { account in
                row(account)
            }
            .onDelete { offsets in
                offsets.map { accounts.accounts[$0].id }.forEach { id in
                    engine.removeAccount(id: id)
                    accounts.delete(id: id)
                }
            }
        }
        .navigationTitle("Accounts")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { creating = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add account")
            }
        }
        .sheet(item: $editing) { account in
            AccountEditorView(account: account)
        }
        .sheet(isPresented: $creating) {
            AccountEditorView(account: .blank())
        }
    }

    private func row(_ account: SipAccount) -> some View {
        let status = engine.registrations[account.id] ?? .unregistered
        return HStack(spacing: 12) {
            Image(systemName: status.systemImage)
                .foregroundStyle(status.tint)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(account.label).font(.body)
                    if account.isDefault {
                        Text("Default")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                    }
                }
                Text(account.addressOfRecord)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text(status.displayName)
                    .font(.caption2).foregroundStyle(status.tint)
            }

            Spacer()

            // The switch is the LOGIN INTENT, not the live state. It survives a restart
            // and a crash, and it is what `restoreRegistrations` reads at launch —
            // without a persisted intent every cold start left the account offline until
            // somebody opened the app, and no call could arrive in between.
            Toggle("", isOn: Binding(
                get: { account.registrationWanted },
                set: { wanted in
                    accounts.setRegistrationWanted(wanted, for: account.id)
                    if wanted {
                        if let fresh = accounts.account(id: account.id) { engine.register(fresh) }
                    } else {
                        engine.unregister(accountId: account.id)
                    }
                }
            ))
            .labelsHidden()
        }
        .contentShape(Rectangle())
        .onTapGesture { editing = account }
    }
}

struct AccountEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var accounts: AccountStore
    @EnvironmentObject private var engine: SipEngine

    @State private var draft: SipAccount
    /// Empty means "unchanged". An existing account's password is never read back out
    /// of the Keychain into the form — it is a credential, and a form that displays it
    /// puts it on screen, in a screenshot and in the accessibility tree.
    @State private var password = ""
    @State private var violations: [String: String] = [:]
    @State private var warnings: [String] = []

    private let isNew: Bool

    init(account: SipAccount) {
        _draft = State(initialValue: account)
        isNew = account.label.isEmpty && account.domain.isEmpty
    }

    var body: some View {
        NavigationView {
            Form {
                Section("Identity") {
                    field("Label", text: $draft.label, key: "LABEL")
                    field("Username", text: $draft.username, key: "USERNAME")
                    field("Domain", text: $draft.domain, key: "DOMAIN")
                    SecureField(isNew ? "Password" : "Password (leave blank to keep)",
                                text: $password)
                    errorText("PASSWORD")
                    field("Display name", text: $draft.displayName, key: "DISPLAY_NAME")
                }

                Section("Server") {
                    Picker("Transport", selection: $draft.transport) {
                        ForEach(SipTransport.allCases) { Text($0.displayName).tag($0) }
                    }
                    portField
                    field("Registrar (optional)", text: $draft.registrar, key: "REGISTRAR")
                    field("Outbound proxy (optional)", text: $draft.outboundProxy, key: "OUTBOUND_PROXY")
                    field("Auth username (optional)", text: $draft.authUsername, key: "AUTH_USERNAME")
                }

                Section {
                    Stepper("Expiry: \(draft.registrationExpirySeconds)s",
                            value: $draft.registrationExpirySeconds, in: 30...86_400, step: 30)
                    errorText("REGISTRATION_EXPIRY")
                } header: {
                    Text("Registration")
                } footer: {
                    Text("The registrar's granted value wins if it is lower. Refresh happens at half the granted lifetime.")
                }

                Section("Media") {
                    Picker("SRTP", selection: $draft.srtpPolicy) {
                        ForEach(SrtpPolicy.allCases) { Text($0.displayName).tag($0) }
                    }
                    if draft.srtpPolicy == .mandatory {
                        Label("Mandatory fails the call rather than downgrading. Some servers answer 488 to any a=crypto on RTP/AVP.",
                              systemImage: "info.circle")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }

                Section("NAT") {
                    Toggle("ICE", isOn: $draft.iceEnabled)
                    field("STUN server (optional)", text: $draft.stunServer, key: "STUN_SERVER")
                    Stepper("Keepalive: \(draft.keepaliveIntervalSeconds)s",
                            value: $draft.keepaliveIntervalSeconds, in: 0...600, step: 5)
                }

                Section {
                    Toggle("Use for outgoing calls", isOn: $draft.isDefault)
                }

                if !warnings.isEmpty {
                    Section("Check") {
                        ForEach(warnings, id: \.self) { warning in
                            Label(warning, systemImage: "exclamationmark.triangle")
                                .font(.caption).foregroundStyle(.orange)
                        }
                    }
                }
            }
            .navigationTitle(isNew ? "New account" : draft.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                }
            }
            .onAppear(perform: validate)
            .onChange(of: draft) { _ in validate() }
        }
    }

    private var portField: some View {
        HStack {
            Text("Port")
            Spacer()
            TextField(String(draft.transport.defaultPort), value: $draft.port,
                      format: .number.grouping(.never))
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 90)
        }
        .overlay(alignment: .bottomLeading) { errorText("PORT").offset(y: 14) }
    }

    private func field(_ title: String, text: Binding<String>, key: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            TextField(title, text: text)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            errorText(key)
        }
    }

    @ViewBuilder
    private func errorText(_ key: String) -> some View {
        if let message = violations[key] {
            Text(message).font(.caption2).foregroundStyle(.red)
        }
    }

    /// Runs the shared validator and keys its violations by field name, which is how
    /// each message lands under the control that caused it.
    private func validate() {
        let report = SharedFacade.shared.validateAccount(
            accountId: draft.id,
            label: draft.label,
            username: draft.username,
            authUsername: draft.authUsername,
            password: password.isEmpty && !isNew ? "placeholder" : password,
            displayName: draft.displayName,
            domain: draft.domain,
            registrar: draft.registrar,
            outboundProxy: draft.outboundProxy,
            port: draft.port > 0 ? String(draft.port) : "",
            transportToken: draft.transport.rawValue,
            registrationExpirySeconds: String(draft.registrationExpirySeconds),
            stunServer: draft.stunServer
        )
        violations = Dictionary(report.violations.map { ($0.field, $0.message) },
                                uniquingKeysWith: { first, _ in first })
        warnings = report.warnings
    }

    private func save() {
        validate()
        guard violations.isEmpty else { return }
        accounts.save(draft, password: password.isEmpty ? nil : password)
        if draft.registrationWanted, let fresh = accounts.account(id: draft.id) {
            engine.register(fresh)
        }
        dismiss()
    }
}
