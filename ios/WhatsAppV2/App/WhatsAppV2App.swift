//
//  WhatsAppV2App.swift
//  The entry point.
//

import SwiftUI

@main
struct WhatsAppV2App: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var container = AppContainer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(container)
                .environmentObject(container.engine)
                .environmentObject(container.accounts)
                .environmentObject(container.callLog)
                .environmentObject(container.settings)
                .environmentObject(container.callKit)
                .environmentObject(container.push)
                .task {
                    // `.task` rather than `.onAppear`: it runs once per view lifetime
                    // and is cancelled with it, where onAppear fires again on every
                    // return to the foreground and would start the stack twice.
                    container.start()
                }
        }
        .onChange(of: scenePhase) { phase in
            // Deliberately NOT stopping the stack on `.background`. A VoIP app that
            // tears down its registration when it leaves the foreground cannot receive
            // a call, which is the entire point of it. iOS keeps the process alive while
            // a call is up and suspends it otherwise; PushKit is what wakes it.
            if phase == .active {
                container.engine.refreshRegistrations()
            }
        }
    }
}
