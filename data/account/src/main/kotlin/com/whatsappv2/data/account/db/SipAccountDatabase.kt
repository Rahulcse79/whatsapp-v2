package com.whatsappv2.data.account.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The account database.
 *
 * ## Migration policy
 *
 * **No destructive migration in release, ever.** `fallbackToDestructiveMigration` would
 * silently delete every configured account on a schema change — including credentials
 * the user may not be able to recover — and it would do so on the users who upgrade
 * first, with no error to point at. Every version bump ships an explicit `Migration`.
 *
 * Schemas are exported to `data/account/schemas` and committed. Room compares against
 * them at build time, so a schema change without a migration is a build failure rather
 * than a field incident.
 *
 * Adding a column: write the `Migration` with `ALTER TABLE ... ADD COLUMN`, give it a
 * default, and add it to [MIGRATIONS]. Removing or retyping one: create the new table,
 * copy, drop, rename — SQLite cannot do it in place.
 */
@Database(
    entities = [SipAccountEntity::class],
    version = SipAccountDatabase.VERSION,
    exportSchema = true,
)
abstract class SipAccountDatabase : RoomDatabase() {

    abstract fun sipAccountDao(): SipAccountDao

    companion object {
        const val VERSION = 1
        const val NAME = "sip-accounts.db"

        /**
         * Every migration, in order.
         *
         * Empty at version 1. It is declared now rather than added later so that the
         * builder already wires it up, and adding a migration is one line in one place
         * instead of a change to the database configuration under time pressure.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
