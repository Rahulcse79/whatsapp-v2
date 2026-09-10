package com.whatsappv2.data.account.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        const val VERSION = 2
        const val NAME = "sip-accounts.db"

        /**
         * Turns ICE off on accounts that were saved while the setting did nothing.
         *
         * A data migration rather than a schema one: the columns are unchanged and the
         * identity hash with them, so this is version 2 only because the *contents* of a
         * column now mean something they did not mean in version 1.
         *
         * Until `ed189b7` the gateway hardcoded `iceEnabled = true` and never read the
         * account at all, so no row in this table records a choice anybody made — every
         * one of them carries the draft's opening value, and every one of them produced
         * an INVITE 54 bytes too large for the path to carry (`SdpBudget`,
         * `NatPolicy.DEFAULT`). Leaving them alone would ship the fix and leave the
         * handset that reported the defect still unable to place a call, because the row
         * on it predates the fix.
         *
         * It is deliberately not conditional. "Only reset rows that look untouched" would
         * need a way to tell a deliberate `true` from the default `true`, and there is no
         * such column — inventing one would be guessing at intent that was never recorded.
         * An account that genuinely wants ICE turns it back on in the account editor, and
         * that choice then survives, because this migration runs once.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sip_accounts SET ice_enabled = 0")
            }
        }

        /** Every migration, in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
