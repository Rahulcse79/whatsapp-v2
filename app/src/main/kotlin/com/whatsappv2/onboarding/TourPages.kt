package com.whatsappv2.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Videocam

/** The tour's copy. See [TourPage] for why each page names a control rather than a benefit. */
internal val TourPages: List<TourPage> = listOf(
    TourPage(
        icon = Icons.AutoMirrored.Filled.Chat,
        title = "Add your extension first",
        body = "CoralX calls through your own SIP account. Add it once and the app keeps " +
            "it registered, so you can be reached without opening anything.",
        where = "Chats → the gear, top right → SIP accounts",
    ),
    TourPage(
        icon = Icons.Filled.Dialpad,
        title = "The dialler is one tap away",
        body = "Type an extension or a full SIP address. Calls go out on your default " +
            "account — the one shown in the Chats bar.",
        where = "Calls → the round button, bottom right",
    ),
    TourPage(
        icon = Icons.Filled.Videocam,
        title = "Turn video on mid-call",
        body = "Any call can become a video call and go back again without hanging up. " +
            "Flip switches between the front and back cameras.",
        where = "In a call → Video, then Flip",
    ),
    TourPage(
        icon = Icons.Filled.AddIcCall,
        title = "Put people together",
        body = "Add a second call, then Merge to mix everyone into one conversation. Up " +
            "to eight, mixed on this phone rather than on a server.",
        where = "In a call → Add, then Merge",
    ),
    TourPage(
        icon = Icons.Filled.FiberManualRecord,
        title = "Record, when you are allowed to",
        body = "Recordings are encrypted and never leave this device. The app asks you to " +
            "confirm before it starts — check what the law where you are requires.",
        where = "In a call → Record",
    ),
    TourPage(
        icon = Icons.Filled.History,
        title = "Everything you missed",
        body = "Every call is in the log, and the dot in the Chats bar says whether you " +
            "are reachable right now: green means registered, red means calls are being " +
            "missed.",
        where = "Calls → search and filters, top right",
    ),
)
