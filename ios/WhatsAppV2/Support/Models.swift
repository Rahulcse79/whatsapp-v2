//
//  Models.swift
//  The app's own value types.
//
//  These are deliberately NOT the Kotlin domain types. The shared module owns the
//  *rules* — what a valid account is, what the retry delay should be, how a dialled
//  string becomes a URI — and `SharedFacade` is how Swift asks. What it does not own is
//  how this app stores and displays things, and importing Kotlin types into every view
//  would couple SwiftUI's diffing to Kotlin/Native's object identity, which is not
//  `Equatable` in the way `@State` needs.
//
//  So: Kotlin decides, Swift holds. Each type below says which shared type it mirrors.
//

import Foundation

// MARK: - Transport

/// Mirrors `com.whatsappv2.domain.model.Transport`, including its default ports.
enum SipTransport: String, Codable, CaseIterable, Identifiable, Sendable {
    case udp, tcp, tls

    var id: String { rawValue }

    /// 5060, or 5061 for TLS. The difference is a common source of "works on UDP,
    /// silently fails on TLS" misconfiguration, which is why it is stated here once.
    var defaultPort: Int { self == .tls ? 5061 : 5060 }

    var isSecure: Bool { self == .tls }

    var displayName: String { rawValue.uppercased() }
}

/// Mirrors `com.whatsappv2.domain.model.SrtpPolicy`.
enum SrtpPolicy: String, Codable, CaseIterable, Identifiable, Sendable {
    case disabled, optional, mandatory

    var id: String { rawValue }

    /// True when a call must fail rather than go out unencrypted.
    var requiresEncryptedMedia: Bool { self == .mandatory }

    var displayName: String {
        switch self {
        case .disabled:  return "Disabled"
        case .optional:  return "Optional"
        case .mandatory: return "Mandatory"
        }
    }
}

// MARK: - Account

/// A configured SIP account.
///
/// Mirrors `com.whatsappv2.domain.model.SipAccount`, with one deliberate difference:
/// **the password is not in it.** On Android the password is a `Secret` encrypted at
/// rest in the database; here it lives in the Keychain under the account id, because a
/// `Codable` struct is written to a plist and a plist is readable by anything that can
/// read the container. `AccountStore` is the only thing that joins the two.
///
/// Registration status is absent for the same reason it is absent on Android: it belongs
/// to the engine, changes many times a second during a retry storm, is not persisted,
/// and a stale copy on disk is how a UI ends up showing "Registered" over a dead
/// transport.
struct SipAccount: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var label: String
    var username: String
    var extensionNumber: String
    var authUsername: String
    var displayName: String
    var domain: String
    var registrar: String
    var outboundProxy: String
    var port: Int
    var transport: SipTransport
    var registrationExpirySeconds: Int
    var stunServer: String
    var iceEnabled: Bool
    var keepaliveIntervalSeconds: Int
    var srtpPolicy: SrtpPolicy
    var audioCodecs: [String]
    var videoCodecs: [String]
    var isDefault: Bool

    /// Whether the user has logged this account in and not logged it out — the intent,
    /// as distinct from the live registration state the engine holds in memory.
    ///
    /// It exists for the reason the Android field does: registration state does not
    /// survive the process and the process does not survive the day. Without a persisted
    /// intent, every cold start left the account offline until somebody opened the app,
    /// and no call could arrive in between.
    var registrationWanted: Bool

    /// The port the account's calls go to. Always written into a dialled URI, even when
    /// it equals the transport default — see `SharedFacade.resolveDialledTarget`.
    var effectivePort: Int { port > 0 ? port : transport.defaultPort }

    /// `authUsername` when set, otherwise `username`. Blank counts as unset.
    var effectiveAuthUsername: String {
        authUsername.trimmingCharacters(in: .whitespaces).isEmpty ? username : authUsername
    }

    /// The AOR this account registers.
    var addressOfRecord: String {
        "\(transport == .tls ? "sips" : "sip"):\(username)@\(domain)"
    }

    static func blank(id: String = UUID().uuidString) -> SipAccount {
        SipAccount(
            id: id, label: "", username: "", extensionNumber: "", authUsername: "",
            displayName: "", domain: "", registrar: "", outboundProxy: "",
            port: 0, transport: .udp,
            // 180 seconds — SipAccount.DEFAULT_EXPIRY_SECONDS. Three minutes puts a
            // refresh inside the lifetime of every NAT mapping worth worrying about.
            registrationExpirySeconds: 180,
            stunServer: "", iceEnabled: true, keepaliveIntervalSeconds: 15,
            srtpPolicy: .disabled,
            audioCodecs: [], videoCodecs: [], isDefault: false,
            registrationWanted: false
        )
    }
}

// MARK: - Registration

/// Mirrors `com.whatsappv2.domain.model.RegistrationState`.
///
/// An enum with associated values rather than a bool plus an optional error, so that the
/// UI cannot show "Registered" while the transport is down: there is no value of this
/// type that means both.
enum RegistrationStatus: Equatable, Sendable {
    case unregistered
    case registering
    /// With the expiry the **server** granted, which may be lower than the one asked for.
    case registered(grantedExpirySeconds: Int)
    case failed(reason: RegistrationFailureReason, retryScheduled: Bool)

    var isUsable: Bool { if case .registered = self { return true }; return false }

    var displayName: String {
        switch self {
        case .unregistered: return "Offline"
        case .registering:  return "Connecting…"
        case .registered:   return "Online"
        case .failed(let reason, _): return reason.displayName
        }
    }
}

/// Mirrors `com.whatsappv2.domain.model.RegistrationFailure`, value for value, so the
/// shared retry policy can be handed the same classification Android gives it.
enum RegistrationFailureReason: String, Equatable, Sendable {
    case authenticationFailed, accountRejected, networkUnavailable
    case timeout, serverUnavailable, transportFailure, invalidConfiguration

    /// True when only the user can resolve it, so retrying is pointless.
    var requiresUserAction: Bool {
        self == .authenticationFailed || self == .accountRejected || self == .invalidConfiguration
    }

    var displayName: String {
        switch self {
        case .authenticationFailed: return "Authentication failed"
        case .accountRejected:      return "Account rejected"
        case .networkUnavailable:   return "No network"
        case .timeout:              return "Server not responding"
        case .serverUnavailable:    return "Server unavailable"
        case .transportFailure:     return "Connection failed"
        case .invalidConfiguration: return "Check the settings"
        }
    }
}

// MARK: - Calls

/// Mirrors `com.whatsappv2.domain.call.CallState`.
enum CallPhase: Equatable, Sendable {
    case idle
    case calling
    case ringing
    /// 183 with SDP: the far end is sending audio before answering. It must be played,
    /// not replaced with a local ring tone.
    case earlyMedia
    case incoming
    case connecting
    case connected
    case held(by: HoldParty)
    case terminated(reason: HangupReason, statusCode: Int?)

    var isActive: Bool {
        switch self {
        case .idle, .terminated: return false
        default: return true
        }
    }

    var isEstablished: Bool {
        switch self {
        case .connected, .held: return true
        default: return false
        }
    }
}

/// Mirrors `com.whatsappv2.domain.call.HoldParty`. Both sides can hold at once, and a
/// local resume must not claim the call is connected while the far end still holds —
/// collapsing this into a boolean is how "resume did nothing" bugs happen.
enum HoldParty: String, Equatable, Sendable {
    case local, remote, both

    func withoutLocal() -> HoldParty? {
        switch self { case .local: return nil; case .remote: return .remote; case .both: return .remote }
    }
    func withoutRemote() -> HoldParty? {
        switch self { case .local: return .local; case .remote: return nil; case .both: return .local }
    }
}

/// Mirrors `com.whatsappv2.domain.model.HangupReason`.
enum HangupReason: String, Codable, Equatable, Sendable {
    case localHangup, remoteHangup, rejected, busy, notFound
    case noAnswer, serverError, networkError, cancelled

    /// True when the call never connected, which is what makes it a missed call rather
    /// than a short one.
    var isFailure: Bool {
        switch self {
        case .localHangup, .remoteHangup: return false
        default: return true
        }
    }
}

/// Mirrors `com.whatsappv2.domain.call.AudioRoute`.
enum AudioRoute: String, CaseIterable, Identifiable, Equatable, Sendable {
    case earpiece, speaker, bluetooth, wiredHeadset

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .earpiece:     return "Phone"
        case .speaker:      return "Speaker"
        case .bluetooth:    return "Bluetooth"
        case .wiredHeadset: return "Headset"
        }
    }

    var systemImage: String {
        switch self {
        case .earpiece:     return "iphone"
        case .speaker:      return "speaker.wave.3.fill"
        case .bluetooth:    return "wave.3.right"
        case .wiredHeadset: return "headphones"
        }
    }
}

/// One live call, as the UI sees it.
struct ActiveCall: Identifiable, Equatable, Sendable {
    let id: Int              // pjsua's call id
    var accountId: String
    var remoteUri: String
    var remoteLabel: String
    var phase: CallPhase
    var isIncoming: Bool
    var isMuted: Bool
    var hasVideo: Bool
    var videoLive: Bool
    var audioLive: Bool
    var connectedAt: Date?
    /// Set once CallKit has a UUID for it, which is what every CallKit action keys on.
    var callKitUUID: UUID

    var isOnHold: Bool { if case .held = phase { return true }; return false }

    /// How long the call has been up, or nil before it connects. Recomputed rather than
    /// stored so a view that redraws every second does not need a timer of its own.
    func duration(at now: Date = Date()) -> TimeInterval? {
        guard let connectedAt else { return nil }
        return now.timeIntervalSince(connectedAt)
    }
}

// MARK: - Call log

/// Mirrors `com.whatsappv2.domain.model.CallLogEntry`.
struct CallLogEntry: Codable, Identifiable, Equatable, Sendable {
    enum Direction: String, Codable, Sendable { case incoming, outgoing }

    let id: UUID
    let accountId: String
    /// The full URI, kept so a redial reaches the same place a year later — not the
    /// label, which loses the host and the port.
    let remoteUri: String
    let remoteLabel: String
    let direction: Direction
    let startedAt: Date
    let durationSeconds: Int
    let hadVideo: Bool
    let reason: HangupReason

    /// A call that never connected and came in. The thing a badge counts.
    var isMissed: Bool {
        direction == .incoming && durationSeconds == 0 && reason.isFailure
    }
}

// MARK: - Settings

/// The app's own preferences. Mirrors the parts of `com.whatsappv2.domain.model.AppSettings`
/// that apply on this platform — the Android-only ones (foreground service behaviour,
/// Telecom integration) have no counterpart and are absent rather than ignored.
struct AppSettings: Codable, Equatable, Sendable {
    var sipTraceEnabled: Bool = false
    var videoFrameRate: Int = 20
    var videoBitrateKbps: Int = 512
    var preferVideoOnOutgoingCalls: Bool = false
    var echoCancellationEnabled: Bool = true
    var callHistoryRetentionDays: Int = 30
    var defaultAudioRoute: AudioRouteSetting = .earpiece
    /// Off by default, and deliberately so: the same control ships off on Android
    /// because a filter that mutes the wrong speaker is worse than no filter.
    var voiceFilterEnabled: Bool = false

    enum AudioRouteSetting: String, Codable, CaseIterable, Sendable {
        case earpiece, speaker
        var route: AudioRoute { self == .speaker ? .speaker : .earpiece }
    }

    static let `default` = AppSettings()
}
