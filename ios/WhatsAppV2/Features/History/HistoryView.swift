//
//  HistoryView.swift
//  Call history.
//

import SwiftUI

struct HistoryView: View {
    @EnvironmentObject private var log: CallLogStore
    @EnvironmentObject private var engine: SipEngine
    @EnvironmentObject private var accounts: AccountStore

    @State private var showMissedOnly = false

    private var shown: [CallLogEntry] {
        showMissedOnly ? log.entries.filter(\.isMissed) : log.entries
    }

    var body: some View {
        Group {
            if log.entries.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(shown) { entry in
                        row(entry)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    log.delete(id: entry.id)
                                } label: { Label("Delete", systemImage: "trash") }
                            }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Recents")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Picker("Filter", selection: $showMissedOnly) {
                    Text("All").tag(false)
                    Text("Missed").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 160)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button(role: .destructive) { log.clear() } label: {
                        Label("Clear history", systemImage: "trash")
                    }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 40)).foregroundStyle(.secondary)
            Text("No calls yet").font(.headline)
            Text("Calls you make and receive appear here.")
                .font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func row(_ entry: CallLogEntry) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon(for: entry))
                .foregroundStyle(entry.isMissed ? .red : .secondary)
                .frame(width: 22)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.remoteLabel)
                    .font(.body)
                    .foregroundStyle(entry.isMissed ? Color.red : Color.primary)
                HStack(spacing: 6) {
                    if entry.hadVideo {
                        Image(systemName: "video.fill").imageScale(.small)
                    }
                    Text(Format.callListDate(entry.startedAt))
                    if let duration = Format.logDuration(entry.durationSeconds) {
                        Text("·"); Text(duration)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            // Redial uses the stored URI, not the label: the label has lost the host and
            // the port, and a year-old row must still reach the same place.
            Button {
                redial(entry, video: false)
            } label: {
                Image(systemName: "phone.fill")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Call \(entry.remoteLabel) back")
        }
        .contentShape(Rectangle())
        .onTapGesture { redial(entry, video: entry.hadVideo) }
    }

    private func icon(for entry: CallLogEntry) -> String {
        if entry.isMissed { return "phone.arrow.down.left" }
        return entry.direction == .incoming ? "phone.arrow.down.left" : "phone.arrow.up.right"
    }

    private func redial(_ entry: CallLogEntry, video: Bool) {
        let account = accounts.account(id: entry.accountId)
            ?? accounts.defaultAccount(registrations: engine.registrations)
        guard let account else { return }
        engine.placeCall(to: entry.remoteUri, from: account, video: video)
    }
}
