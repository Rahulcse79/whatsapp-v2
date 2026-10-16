package com.whatsappv2.core.common.time

/** JVM and Android: the wall clock the whole platform already agrees on. */
actual object SystemClock : Clock {
    actual override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
