//
//  RootView.swift
//  The tab shell, and the in-call screen over it.
//

import SwiftUI

struct RootView: View {
    @EnvironmentObject private var engine: SipEngine
    @EnvironmentObject private var callLog: CallLogStore
    @EnvironmentObject private var accounts: AccountStore

    @State private var tab: Tab = .dialer

    enum Tab: Hashable { case dialer, recents, accounts, settings }

    var body: some View {
        ZStack {
            TabView(selection: $tab) {
                NavigationView { DialerView() }
                    .tabItem { Label("Keypad", systemImage: "circle.grid.3x3.fill") }
                    .tag(Tab.dialer)

                NavigationView { HistoryView() }
                    .tabItem { Label("Recents", systemImage: "clock.arrow.circlepath") }
                    .badge(callLog.missedCount)
                    .tag(Tab.recents)

                NavigationView { AccountsView() }
                    .tabItem { Label("Accounts", systemImage: "person.crop.circle") }
                    .tag(Tab.accounts)

                NavigationView { SettingsView() }
                    .tabItem { Label("Settings", systemImage: "gearshape") }
                    .tag(Tab.settings)
            }
            // `.stack` on every size class. The default on iPad is a split view, and a
            // dialler rendered as a sidebar plus an empty detail pane is not a thing
            // anybody wants.
            .navigationViewStyle(.stack)

            // The call screen covers the tabs rather than being pushed into one of them,
            // because a call is modal to the whole app: tapping Recents mid-call must
            // not hide the hang-up button.
            if engine.focusedCall != nil {
                CallView()
                    .transition(.move(edge: .bottom))
                    .zIndex(1)
            }

            if accounts.accounts.isEmpty {
                // Not an alert: an alert is dismissed and forgotten. A persistent hint
                // that leads to the thing that fixes it is what a first launch needs.
                firstRunHint
            }
        }
        .animation(.easeInOut(duration: 0.25), value: engine.focusedCall?.id)
    }

    private var firstRunHint: some View {
        VStack {
            Spacer()
            Button {
                tab = .accounts
            } label: {
                Label("Add a SIP account to get started", systemImage: "arrow.right.circle.fill")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(.ultraThinMaterial))
            }
            .padding(.bottom, 70)
        }
        .allowsHitTesting(true)
    }
}
