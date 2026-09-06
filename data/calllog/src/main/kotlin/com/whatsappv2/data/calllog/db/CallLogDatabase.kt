package com.whatsappv2.data.calllog.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The call log database.
 *
 * ## Its own database, not a table in the account one
 *
 * The two have different lifetimes and different stakes. Accounts hold credentials and
 * must never be dropped; a call log is history and a user may reasonably clear it. Keeping
 * them apart means "clear all history" cannot be one mistaken `Migration` away from
 * deleting the accounts too, and a corrupt log cannot take the accounts with it.
 *
 * ## Migration policy
 *
 * The same as the account database's: no destructive fallback, ever. History that
 * disappears on upgrade is indistinguishable from a bug, and the user has no way to get
 * it back. Every version bump ships an explicit [Migration].
 *
 * Schemas are exported to `data/calllog/schemas` and committed, so a schema change
 * without a migration is a build failure rather than a field incident.
 */
@Database(
    entities = [CallLogEntity::class],
    version = CallLogDatabase.VERSION,
    exportSchema = true,
)
abstract class CallLogDatabase : RoomDatabase() {

    abstract fun callLogDao(): CallLogDao

    companion object {
        const val VERSION = 1
        const val NAME = "call-log.db"

        /** Every migration, in order. Empty at version 1, declared so adding one is a line. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
