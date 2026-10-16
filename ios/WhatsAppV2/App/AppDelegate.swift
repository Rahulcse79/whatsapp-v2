//
//  AppDelegate.swift
//
//  SwiftUI's `App` has no hook for the things iOS still asks a UIKit delegate about, and
//  for a VoIP app two of them matter: the audio session has to exist before CallKit ever
//  activates it, and `application(_:didFinishLaunchingWithOptions:)` is the earliest
//  point at which that is true.
//

import UIKit
import AVFoundation
import PushKit

final class AppDelegate: NSObject, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {

        // The category is set here and not on the first call, because the system can
        // activate the session before the app has had a chance to configure it: a VoIP
        // push launches the process and CallKit may call `didActivate` within
        // milliseconds. A session still in its default `.soloAmbient` category at that
        // moment cannot record, and the call's first seconds have no microphone.
        //
        // `setActive` is still NOT called. That remains CallKit's, always.
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playAndRecord, mode: .voiceChat,
                options: [.allowBluetooth, .allowBluetoothA2DP, .duckOthers]
            )
        } catch {
            NSLog("[app] could not pre-configure the audio session: \(error.localizedDescription)")
        }

        return true
    }

    /// Background audio keeps the process alive while a call is up; this is the
    /// belt-and-braces half for the moment between a push and CallKit reporting.
    func applicationDidEnterBackground(_ application: UIApplication) { }
}
