package com.whatsappv2.data.calllog.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        const val VERSION = 3
        const val NAME = "call-log.db"

        /**
         * Records which server each call was on, so a call back survives the server moving.
         *
         * `remote_uri` carries the account's domain in its host — the extension was
         * completed against it, or the server put its own address in `From` — and until
         * this version nothing recorded that the host *was* the domain. So when the domain
         * changed (a laptop-hosted PBX on a new Wi-Fi network, 2026-09-14), every call back
         * from history sent its INVITE to the previous address and timed out, while the
         * row still read "1003". `account_domain` is what lets
         * [com.whatsappv2.domain.model.CallLogEntry.redialTarget] tell the two apart.
         *
         * Nullable, and left null on the rows that exist: the information was never
         * written and cannot be recovered. Those rows are read as being on the account's
         * own server — see `redialTarget` — which is what every one of them was.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_log ADD COLUMN account_domain TEXT")
            }
        }

        /**
         * Records that a call was part of a conference, of either kind.
         *
         * A dial-in room is one call and was already one row; a conference this device
         * mixed is several calls, and until this version the history screen showed them
         * as unrelated calls to unrelated people — a merged three-way read as two
         * separate calls with no sign they had ever been the same conversation.
         *
         * `NOT NULL DEFAULT 0` rather than nullable: the column has a true answer for
         * every future row, and the existing rows are being told "not a conference",
         * which is what almost all of them were. A nullable column would offer a third
         * state that nothing can ever distinguish from the second.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_log ADD COLUMN is_conference INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Every migration, in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
