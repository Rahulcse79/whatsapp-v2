package com.whatsappv2.feature.chat.thread

/**
 * The emoji the picker offers, in the order it offers them.
 *
 * ## Why a hand-written list rather than the system's
 *
 * Android has no public API that enumerates the emoji a device can draw. `EmojiCompat`
 * can tell you whether one *renders*, but it is a check, not a catalogue — so every
 * keyboard and every chat app ships a list. This is that list, kept to the characters
 * that have been in Unicode long enough to render on an Android 11 handset without a
 * downloadable font, because an emoji that arrives as a dotted box is worse than one that
 * was never offered.
 *
 * ## It is content, so it lives away from the layout
 *
 * The picker is a grid of strings. Keeping them here means the grid is twelve lines of
 * Compose rather than six hundred, and means adding a category is a data change.
 *
 * Each emoji is a String rather than a Char: most of these are above the basic plane
 * (two Kotlin chars), and the gestures carry no skin-tone modifier on purpose — offering
 * one tone and not the others is a worse answer than offering the neutral yellow.
 *
 * Nothing here is a type — [EmojiCategory] has its own file — so this one stays what its
 * name says it is: the data.
 */
internal val EMOJI_CATEGORIES: List<EmojiCategory> = listOf(
    EmojiCategory(
        icon = "🙂",
        name = "Smileys",
        emoji = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
            "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰",
            "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜",
            "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "😏", "😒",
            "😞", "😔", "😟", "😕", "🙁", "😣", "😖", "😫",
            "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
            "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥",
            "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐",
            "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲",
            "🥱", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢",
            "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈",
            "👿", "👻", "💀", "☠️", "👽", "🤖", "💩", "🎃",
        ),
    ),
    EmojiCategory(
        icon = "👍",
        name = "Gestures",
        emoji = listOf(
            "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙",
            "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️",
            "🖖", "👋", "🤝", "🙏", "✊", "👊", "🤛", "🤜",
            "👏", "🙌", "👐", "🤲", "💪", "🦾", "✍️", "💅",
            "👂", "👃", "🧠", "👀", "👁️", "👅", "👄", "🫀",
        ),
    ),
    EmojiCategory(
        icon = "❤️",
        name = "Hearts",
        emoji = listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖",
            "💘", "💝", "💟", "♥️", "💋", "💌", "🔥", "✨",
            "⭐", "🌟", "💫", "⚡", "💥", "💢", "💦", "💤",
        ),
    ),
    EmojiCategory(
        icon = "🐶",
        name = "Animals and nature",
        emoji = listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈",
            "🙉", "🙊", "🐒", "🦄", "🐴", "🦋", "🐌", "🐞",
            "🐝", "🐢", "🐍", "🐙", "🦀", "🐠", "🐟", "🐬",
            "🐳", "🦈", "🐊", "🐘", "🦒", "🦓", "🐪", "🐓",
            "🦅", "🦉", "🌵", "🌲", "🌳", "🌴", "🌱", "🌿",
            "🍀", "🍁", "🍂", "🌸", "🌹", "🌺", "🌻", "🌼",
            "🌙", "☀️", "⛅", "☁️", "🌧️", "⛈️", "❄️", "🌈",
        ),
    ),
    EmojiCategory(
        icon = "🍕",
        name = "Food and drink",
        emoji = listOf(
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
            "🍓", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝",
            "🍅", "🥑", "🥦", "🥕", "🌽", "🌶️", "🥔", "🍠",
            "🥐", "🍞", "🥖", "🧀", "🥚", "🍳", "🥞", "🧇",
            "🥓", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯",
            "🥗", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟",
            "🍚", "🍙", "🍘", "🍢", "🍡", "🍧", "🍨", "🍦",
            "🍰", "🎂", "🍫", "🍬", "🍭", "🍮", "☕", "🍵",
            "🥤", "🧃", "🍺", "🍻", "🥂", "🍷", "🥃", "🧊",
        ),
    ),
    EmojiCategory(
        icon = "⚽",
        name = "Activity and travel",
        emoji = listOf(
            "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱",
            "🏓", "🏸", "🥅", "🏒", "🏑", "🏏", "⛳", "🏹",
            "🎣", "🥊", "🥋", "🎽", "⛸️", "🎿", "🛷", "🏂",
            "🏆", "🥇", "🥈", "🥉", "🎯", "🎲", "🎮", "🎰",
            "🎸", "🥁", "🎹", "🎺", "🎻", "🎤", "🎧", "🎬",
            "🚗", "🚕", "🚙", "🚌", "🚑", "🚒", "🚓", "🏍️",
            "🚲", "🛵", "✈️", "🚀", "🛸", "🚁", "⛵", "🚢",
            "🚂", "🚆", "🗺️", "🏖️", "🏝️", "🏔️", "🗻", "🎡",
        ),
    ),
    EmojiCategory(
        icon = "💡",
        name = "Objects and symbols",
        emoji = listOf(
            "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "💾",
            "📷", "📸", "📹", "🎥", "📞", "☎️", "📟", "📠",
            "📺", "📻", "⏰", "⏱️", "⌛", "🔋", "🔌", "💡",
            "🔦", "🕯️", "📔", "📕", "📖", "📚", "📝", "✏️",
            "📌", "📎", "✂️", "🔑", "🔒", "🔓", "🔨", "🪛",
            "🔧", "⚙️", "🧲", "💰", "💳", "💎", "⚖️", "🧪",
            "💊", "🩹", "🚪", "🛏️", "🚿", "🧹", "🎁", "🎈",
            "🎉", "🎊", "✅", "❌", "❗", "❓", "💯", "🔔",
        ),
    ),
)
