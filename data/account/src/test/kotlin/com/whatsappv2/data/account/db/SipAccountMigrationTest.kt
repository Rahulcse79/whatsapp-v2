package com.whatsappv2.data.account.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The version 1 → 2 migration, against a version 1 database built by hand.
 *
 * ## Why not `MigrationTestHelper`
 *
 * It reads the exported schemas out of an instrumentation APK's assets, which a JVM test
 * does not have. Creating the old database from the `createSql` that
 * `schemas/…SipAccountDatabase/1.json` records tests the same thing and tests it here:
 * the table is the one that shipped, byte for byte, and `room_master_table` carries
 * version 1's identity hash, so Room validates the result exactly as it does on a
 * handset. If the entity ever changes without a schema migration, this test stops
 * opening the database and says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SipAccountMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: SipAccountDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `an account saved before the fix has ICE turned off, and keeps everything else`() {
        writeVersionOne { db ->
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-1", iceEnabled = 1))
        }

        val migrated = openWithMigrations()

        // The whole point: the row on the handset that reported the defect predates the
        // fix, so shipping the new default alone would leave it offering ICE candidates
        // and an INVITE 54 bytes too big for the path (SdpBudget, NatPolicy.DEFAULT).
        assertEquals(0, migrated.intOf("SELECT ice_enabled FROM sip_accounts WHERE id = 'acct-1'"))
        // STUN is a different setting and this migration is not about it. Left as it was,
        // because a migration that quietly changed a second column would be one nobody
        // could reason about from its name.
        assertEquals(1, migrated.intOf("SELECT stun_enabled FROM sip_accounts WHERE id = 'acct-1'"))
        assertEquals(
            "alice-acct-1",
            migrated.stringOf("SELECT username FROM sip_accounts WHERE id = 'acct-1'"),
        )
        assertEquals(
            "hunter22-ciphertext",
            migrated.stringOf("SELECT password_ciphertext FROM sip_accounts WHERE id = 'acct-1'"),
        )
    }

    @Test
    fun `every account is reset, not only the first one`() {
        writeVersionOne { db ->
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-1", iceEnabled = 1))
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-2", iceEnabled = 1))
            // Already off. An UPDATE with no WHERE covers it too, and asserting it here
            // is what stops a later "optimisation" adding a clause that misses a row.
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-3", iceEnabled = 0))
        }

        val migrated = openWithMigrations()

        assertEquals(0, migrated.intOf("SELECT MAX(ice_enabled) FROM sip_accounts"))
        assertEquals(3, migrated.intOf("SELECT COUNT(*) FROM sip_accounts"))
    }

    @Test
    fun `version 3 adds the login intent, unset, and keeps everything else`() {
        // Written as version 1 and carried through both migrations: a device that skipped
        // an app version takes the whole chain, and the chain is what has to work.
        writeVersionOne { db ->
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-1", iceEnabled = 0))
        }

        val migrated = openWithMigrations()

        assertEquals(0, migrated.intOf("SELECT registration_wanted FROM sip_accounts WHERE id = 'acct-1'"))
        assertEquals("alice-acct-1", migrated.stringOf("SELECT username FROM sip_accounts WHERE id = 'acct-1'"))
    }

    @Test
    fun `an account saved on the OPTIONAL default is moved to DISABLED, and MANDATORY is not`() {
        // MIGRATION_1_2's argument, again: OPTIONAL was the draft's opening value, nobody
        // chose it, and on FreeSWITCH it fails every outgoing call (488, "a=crypto in
        // RTP/AVP, refer to rfc3711" — measured 2026-09-10). MANDATORY is a choice.
        writeVersionOne { db ->
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-1", iceEnabled = 0, srtp = "OPTIONAL"))
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-2", iceEnabled = 0, srtp = "MANDATORY"))
            db.insert("sip_accounts", CONFLICT_FAIL, accountRow(id = "acct-3", iceEnabled = 0, srtp = "DISABLED"))
        }

        val migrated = openWithMigrations()

        assertEquals("DISABLED", migrated.stringOf("SELECT srtp_policy FROM sip_accounts WHERE id = 'acct-1'"))
        assertEquals("MANDATORY", migrated.stringOf("SELECT srtp_policy FROM sip_accounts WHERE id = 'acct-2'"))
        assertEquals("DISABLED", migrated.stringOf("SELECT srtp_policy FROM sip_accounts WHERE id = 'acct-3'"))
    }

    @Test
    fun `the migrations are the ones the builder installs`() {
        // DatabaseModule adds SipAccountDatabase.MIGRATIONS and nothing else, so a
        // migration that exists but is not in the array would pass the tests above and
        // still delete every account on a real device.
        assertTrue(SipAccountDatabase.MIGRATIONS.any { it.startVersion == 1 && it.endVersion == 2 })
        assertTrue(SipAccountDatabase.MIGRATIONS.any { it.startVersion == 2 && it.endVersion == 3 })
        assertEquals(3, SipAccountDatabase.VERSION)
    }

    /**
     * Creates the database exactly as version 1 left it.
     *
     * `room_master_table` matters as much as the table itself: without version 1's
     * identity hash in it Room refuses to open the file at all, and the test would be
     * asserting against a database Room had just created from scratch.
     */
    private fun writeVersionOne(fill: (SupportSQLiteDatabase) -> Unit) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(VERSION_1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(VERSION_1_CREATE_SQL)
                        VERSION_1_INDEX_SQL.forEach(db::execSQL)
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                "VALUES (42, '$VERSION_1_IDENTITY_HASH')",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase.use(fill)
        }
    }

    private fun openWithMigrations(): SipAccountDatabase =
        Room.databaseBuilder(context, SipAccountDatabase::class.java, DB_NAME)
            .addMigrations(*SipAccountDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    private fun SipAccountDatabase.intOf(sql: String): Int =
        openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SipAccountDatabase.stringOf(sql: String): String? =
        openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun accountRow(id: String, iceEnabled: Int, srtp: String = "OPTIONAL") = ContentValues().apply {
        put("id", id)
        put("label", "Work")
        // Distinct per row: `index_sip_accounts_username_domain` is unique, so three
        // accounts on one identity is a constraint violation rather than a fixture.
        put("username", "alice-$id")
        put("password_ciphertext", "hunter22-ciphertext")
        put("domain", "sip.example.com")
        put("transport", "UDP")
        put("registration_expiry_seconds", 3_600)
        put("ice_enabled", iceEnabled)
        put("stun_enabled", 1)
        put("keepalive_interval_seconds", 30)
        put("srtp_policy", srtp)
        put("audio_codecs", "PCMU")
        put("video_codecs", "")
        put("is_default", 0)
        put("created_at_epoch_millis", 1_000L)
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val VERSION_1 = 1

        /** Copied verbatim from `schemas/…SipAccountDatabase/1.json`. */
        const val VERSION_1_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `sip_accounts` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`username` TEXT NOT NULL, `extension` TEXT, `auth_username` TEXT, " +
                "`password_ciphertext` TEXT NOT NULL, `display_name` TEXT, `domain` TEXT NOT NULL, " +
                "`registrar` TEXT, `outbound_proxy` TEXT, `port` INTEGER, `transport` TEXT NOT NULL, " +
                "`registration_expiry_seconds` INTEGER NOT NULL, `stun_server` TEXT, " +
                "`turn_server` TEXT, `turn_username` TEXT, `turn_password_ciphertext` TEXT, " +
                "`ice_enabled` INTEGER NOT NULL, `stun_enabled` INTEGER NOT NULL, " +
                "`keepalive_interval_seconds` INTEGER NOT NULL, `srtp_policy` TEXT NOT NULL, " +
                "`audio_codecs` TEXT NOT NULL, `video_codecs` TEXT NOT NULL, " +
                "`is_default` INTEGER NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"

        /**
         * The indices, also verbatim from `1.json`.
         *
         * Not optional decoration: Room compares the whole `TableInfo` after a migration
         * and a database missing them is a database it refuses to open — which is exactly
         * how a real migration that forgot to recreate an index would fail on a handset.
         */
        val VERSION_1_INDEX_SQL = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sip_accounts_username_domain` " +
                "ON `sip_accounts` (`username`, `domain`)",
            "CREATE INDEX IF NOT EXISTS `index_sip_accounts_is_default` " +
                "ON `sip_accounts` (`is_default`)",
        )

        /** Also from `1.json`. Room refuses to open a database whose hash it cannot match. */
        const val VERSION_1_IDENTITY_HASH = "906670b073d7dab9cf020a330ef42c4a"
    }
}
