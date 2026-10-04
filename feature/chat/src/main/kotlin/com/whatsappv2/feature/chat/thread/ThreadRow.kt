package com.whatsappv2.feature.chat.thread

import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What one row of the thread is.
 *
 * A sealed type rather than a nullable "header" field on the message, because a day
 * capsule is not a message: it has no sender, no delivery state and no retry, and every
 * one of those would have to be made nullable to pretend otherwise.
 */
internal sealed interface ThreadRow {

    /** A stable list key. The id when the server has given one, the client id until then. */
    val key: String

    /** A floating date capsule between two days of conversation. */
    data class Day(val label: String) : ThreadRow {
        override val key: String get() = "day-$label"
    }

    /**
     * One message.
     *
     * [showSender] is decided here rather than in the composable, because it depends on
     * the row **before** this one — a name is drawn once at the top of a run, not above
     * every bubble, which is what keeps a back-and-forth readable.
     */
    data class Said(val message: ChatMessage, val showSender: Boolean) : ThreadRow {
        override val key: String
            get() = message.id?.value ?: message.clientId ?: "${message.createdAtMs}-${message.body}"
    }
}

/**
 * Groups a thread into day capsules and runs of consecutive messages.
 *
 * Both are the same idea: a conversation is read as a sequence of moments, and repeating
 * the date on every line — or the sender on every bubble — is noise that hides the one
 * place the information actually changes.
 */
internal fun List<ChatMessage>.withDayBreaks(): List<ThreadRow> {
    val rows = mutableListOf<ThreadRow>()
    var lastDay: String? = null
    var lastSender: String? = null

    forEach { message ->
        val day = message.dayLabel()
        if (day != lastDay) {
            rows += ThreadRow.Day(day)
            lastDay = day
            // A new day restarts the run, so the first bubble under a capsule is always
            // named. Otherwise yesterday's last speaker silently owns today's first line.
            lastSender = null
        }

        rows += ThreadRow.Said(message, showSender = message.senderId != lastSender)
        lastSender = message.senderId
    }
    return rows
}

/** `14:05`. The platform's 24-hour setting is not consulted; the format is the app's. */
internal fun ChatMessage.timeLabel(): String = TIME.format(Date(createdAtMs))

/**
 * What to draw for a message.
 *
 * Only TEXT can be **sent** — `WsClient.sendMessage` hardcodes it — but IMAGE, VIDEO,
 * AUDIO, DOCUMENT and types this build has never heard of can all **arrive**. Naming them
 * is how a thread stays readable instead of showing a run of empty bubbles.
 */
internal fun ChatMessage.displayBody(): String = when (type) {
    ChatMessageType.TEXT, ChatMessageType.SYSTEM -> body.orEmpty()
    ChatMessageType.IMAGE -> "📷 Photo"
    ChatMessageType.VIDEO -> "🎬 Video"
    ChatMessageType.AUDIO -> "🎤 Audio message"
    ChatMessageType.DOCUMENT -> "📄 Document"
    ChatMessageType.UNKNOWN -> "Unsupported message"
}

/**
 * "Today", "Yesterday", or the date.
 *
 * Relative for the two days somebody is likely to be scrolling through, absolute beyond
 * that — a thread from March does not become more readable for saying "38 days ago".
 */
private fun ChatMessage.dayLabel(): String {
    val message = Calendar.getInstance().apply { timeInMillis = createdAtMs }
    val today = Calendar.getInstance()

    if (message.isSameDayAs(today)) return "Today"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (message.isSameDayAs(yesterday)) return "Yesterday"

    val format = if (message.get(Calendar.YEAR) == today.get(Calendar.YEAR)) DAY else DAY_WITH_YEAR
    return format.format(Date(createdAtMs))
}

private fun Calendar.isSameDayAs(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

/** `Locale.getDefault()`, so a date reads the way the phone's owner expects it to. */
private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
private val DAY = SimpleDateFormat("d MMMM", Locale.getDefault())
private val DAY_WITH_YEAR = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
