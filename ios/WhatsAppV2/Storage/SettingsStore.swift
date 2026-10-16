//
//  SettingsStore.swift
//  Preferences, in UserDefaults.
//
//  One JSON blob under one key rather than a key per field. The whole struct is read and
//  written together, nothing else reads individual keys, and a single value means a
//  partial write is impossible — which a field-per-key layout makes easy and silent when
//  a new field is added and an old build writes the dictionary back without it.
//

import Foundation
import Combine

@MainActor
final class SettingsStore: ObservableObject {

    @Published private(set) var current: AppSettings

    private let defaults: UserDefaults
    private static let key = "com.whatsappv2.settings"

    /// Fires after any change, so the engine can push the new media settings into PJSIP.
    var onChange: ((AppSettings) -> Void)?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.key),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            current = decoded
        } else {
            current = .default
        }
    }

    func update(_ mutate: (inout AppSettings) -> Void) {
        var next = current
        mutate(&next)
        guard next != current else { return }
        current = next
        persist()
        onChange?(next)
    }

    func reset() {
        current = .default
        persist()
        onChange?(current)
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(current) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
