//
//  Formatting.swift
//  Display helpers, in one place so two screens cannot disagree.
//

import Foundation
import SwiftUI

enum Format {

    /// `7:04`, or `1:02:33` once a call passes an hour.
    ///
    /// Hand-rolled rather than `DateComponentsFormatter`: the formatter's `.positional`
    /// style drops the leading zero on the minutes of a call under ten minutes, so a
    /// timer that reads `7:04` one second and `8:04` the next would read `7:4`.
    static func duration(_ interval: TimeInterval) -> String {
        let total = max(0, Int(interval))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    /// `14:32` today, `Yesterday`, `Mon`, then a date. What a call list wants: the
    /// precision people actually need falls off sharply with age.
    static func callListDate(_ date: Date, now: Date = Date()) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        if let days = calendar.dateComponents([.day], from: date, to: now).day, days < 7 {
            return date.formatted(.dateTime.weekday(.abbreviated))
        }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    /// A duration for the history list: `4 min 12 sec`, or nothing for a missed call.
    static func logDuration(_ seconds: Int) -> String? {
        guard seconds > 0 else { return nil }
        if seconds < 60 { return "\(seconds) sec" }
        let minutes = seconds / 60
        let remainder = seconds % 60
        return remainder == 0 ? "\(minutes) min" : "\(minutes) min \(remainder) sec"
    }
}

extension RegistrationStatus {
    var tint: Color {
        switch self {
        case .registered:   return .green
        case .registering:  return .orange
        case .unregistered: return .secondary
        case .failed(let reason, _):
            // A failure the user must fix is red; one that will retry by itself is
            // orange. The difference is whether the screen is asking for something.
            return reason.requiresUserAction ? .red : .orange
        }
    }

    var systemImage: String {
        switch self {
        case .registered:   return "checkmark.circle.fill"
        case .registering:  return "arrow.triangle.2.circlepath"
        case .unregistered: return "circle"
        case .failed:       return "exclamationmark.triangle.fill"
        }
    }
}
