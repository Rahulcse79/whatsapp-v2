//
//  CallLogStore.swift
//  The call history.
//
//  A JSON file rather than Core Data or SQLite, and that is a sized decision rather than
//  a shortcut: the list is bounded by the retention setting (30 days by default), every
//  read is "give me all of it, newest first", and there is no query that a database
//  would make faster. Android uses Room because it also stores chat; this does not.
//
//  Retention is enforced on every write rather than by a background job, because a
//  background job on iOS runs when the system feels like it and the user who sets
//  "keep 7 days" means it.
//

import Foundation
import Combine

@MainActor
final class CallLogStore: ObservableObject {

    @Published private(set) var entries: [CallLogEntry] = []

    private let fileURL: URL
    private var retentionDays: Int

    /// A hard ceiling on top of the retention window.
    ///
    /// Retention is in days and a busy extension can make thousands of calls in thirty
    /// of them. The list is loaded whole and rendered in a `List`, so a cap keeps both
    /// bounded; 2000 rows is far more history than anyone scrolls and about 400 KB.
    private static let maximumEntries = 2000

    init(directory: URL? = nil, retentionDays: Int = 30) {
        self.retentionDays = retentionDays
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WhatsAppV2", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.fileURL = base.appendingPathComponent("call-log.json")
        load()
    }

    var missedCount: Int { entries.filter(\.isMissed).count }

    func record(_ entry: CallLogEntry) {
        entries.insert(entry, at: 0)
        prune()
        persist()
    }

    func clear() {
        entries.removeAll()
        persist()
    }

    func delete(id: UUID) {
        entries.removeAll { $0.id == id }
        persist()
    }

    func setRetention(days: Int) {
        retentionDays = days
        prune()
        persist()
    }

    /// Everything for one correspondent, for a "call details" view.
    func entries(forRemote uri: String) -> [CallLogEntry] {
        entries.filter { $0.remoteUri == uri }
    }

    private func prune() {
        if retentionDays > 0 {
            let cutoff = Date().addingTimeInterval(-Double(retentionDays) * 86_400)
            entries.removeAll { $0.startedAt < cutoff }
        }
        if entries.count > Self.maximumEntries {
            entries.removeLast(entries.count - Self.maximumEntries)
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        entries = (try? decoder.decode([CallLogEntry].self, from: data)) ?? []
        prune()
    }

    private func persist() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(entries) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
