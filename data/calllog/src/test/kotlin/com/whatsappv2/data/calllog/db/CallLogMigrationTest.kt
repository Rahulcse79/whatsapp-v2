package com.whatsappv2.data.calllog.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase.CONFLICT_FAIL
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.data.calllog.CALL_LOG_ROBOLECTRIC_SDK
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version 1 → 2 migration, against a version 1 database built by hand.
 *
 * The same shape as the account module's migration test, for the same reason: a JVM test
 * has no instrumentation APK for `MigrationTestHelper` to read exported schemas from, so
 * the old database is created from the `createSql` that `schemas/…CallLogDatabase/1.json`
 * records, with version 1's identity hash in `room_master_table`. Room then validates the
 * migrated table exactly as it does on a handset, and an entity change without a migration
 * stops this test opening the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CALL_LOG_ROBOLECTRIC_SDK])
class CallLogMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: CallLogDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `a call logged before version 2 keeps everything and has no domain recorded`() {
        // The row on the handset that showed the defect: logged on the home network's
        // address, and no way to know from the row that the address was the server's. The
        // migration cannot recover that, and must not pretend to — null is what the domain
        // model reads as "before it was recorded" (CallLogEntry.redialTarget).
        writeVersionOne { db ->
            db.insert("call_log", CONFLICT_FAIL, callRow(remote = "sip:1003@192.168.2.196"))
        }

        val migrated = openWithMigrations()

        assertNull(migrated.stringOf("SELECT account_domain FROM call_log WHERE id = 1"))
        assertEquals("sip:1003@192.168.2.196", migrated.stringOf("SELECT remote_uri FROM call_log WHERE id = 1"))
        assertEquals("acct-1", migrated.stringOf("SELECT account_id FROM call_log WHERE id = 1"))
        assertEquals("OUTGOING", migrated.stringOf("SELECT direction FROM call_log WHERE id = 1"))
        assertEquals(1, migrated.intOf("SELECT has_audio FROM call_log WHERE id = 1"))
    }

    @Test
    fun `a call logged after the migration carries its domain, so the two are told apart`() {
        writeVersionOne { db ->
            db.insert("call_log", CONFLICT_FAIL, callRow(remote = "sip:1003@192.168.2.196"))
        }

        val migrated = openWithMigrations()
        migrated.openHelper.writableDatabase.insert(
            "call_log",
            CONFLICT_FAIL,
            callRow(remote = "sip:1003@192.168.0.101").apply { put("account_domain", "192.168.0.101") },
        )

        assertNull(migrated.stringOf("SELECT account_domain FROM call_log WHERE id = 1"))
        assertEquals("192.168.0.101", migrated.stringOf("SELECT account_domain FROM call_log WHERE id = 2"))
        assertEquals(2, migrated.intOf("SELECT COUNT(*) FROM call_log"))
    }

    @Test
    fun `a call logged before version 3 is read as not a conference, and later rows can say`() {
        // The column has a true answer for every row written from now on and no answer at
        // all for the rows that already exist. NOT NULL DEFAULT 0 tells those "not a
        // conference", which is what almost all of them were — and, crucially, is a value
        // the domain model can read, unlike a null that nothing could tell from a false.
        writeVersionOne { db ->
            db.insert("call_log", CONFLICT_FAIL, callRow(remote = "sip:1003@192.168.0.101"))
        }

        val migrated = openWithMigrations()
        migrated.openHelper.writableDatabase.insert(
            "call_log",
            CONFLICT_FAIL,
            callRow(remote = "sip:1002@192.168.0.101").apply { put("is_conference", 1) },
        )

        assertEquals(0, migrated.intOf("SELECT is_conference FROM call_log WHERE id = 1"))
        assertEquals(1, migrated.intOf("SELECT is_conference FROM call_log WHERE id = 2"))
    }

    @Test
    fun `the migrations are the ones the builder installs`() {
        // CallLogModule adds CallLogDatabase.MIGRATIONS and nothing else, so a migration
        // that exists but is not in the array would pass the tests above and still refuse
        // to open — there is no destructive fallback — every history on every device.
        assertTrue(CallLogDatabase.MIGRATIONS.any { it.startVersion == 1 && it.endVersion == 2 })
        assertTrue(CallLogDatabase.MIGRATIONS.any { it.startVersion == 2 && it.endVersion == 3 })
        assertEquals(3, CallLogDatabase.VERSION)
        // A chain with a gap opens nothing: Room walks 1 -> 2 -> 3 and refuses the file if
        // any step is missing, so the count matters as much as the endpoints.
        assertEquals(CallLogDatabase.VERSION - 1, CallLogDatabase.MIGRATIONS.size)
    }

    /**
     * Creates the database exactly as version 1 left it, `room_master_table` included:
     * without version 1's identity hash Room refuses the file, and the test would be
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

    private fun openWithMigrations(): CallLogDatabase =
        Room.databaseBuilder(context, CallLogDatabase::class.java, DB_NAME)
            .addMigrations(*CallLogDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    private fun CallLogDatabase.intOf(sql: String): Int =
        openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun CallLogDatabase.stringOf(sql: String): String? =
        openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    /** A version 1 row: every column version 1 had, and nothing it did not. */
    private fun callRow(remote: String) = ContentValues().apply {
        put("account_id", "acct-1")
        put("remote_uri", remote)
        put("direction", "OUTGOING")
        put("started_at_epoch_millis", STARTED_AT)
        put("ended_at_epoch_millis", STARTED_AT + ONE_MINUTE)
        put("reason", "LOCAL_HANGUP")
        put("has_audio", 1)
        put("has_video", 0)
    }

    private companion object {
        const val DB_NAME = "call-log-migration-test.db"
        const val VERSION_1 = 1
        const val STARTED_AT = 1_700_000_000_000L
        const val ONE_MINUTE = 60_000L

        /** Copied verbatim from `schemas/…CallLogDatabase/1.json`. */
        const val VERSION_1_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `call_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`account_id` TEXT NOT NULL, `remote_uri` TEXT NOT NULL, `remote_display_name` TEXT, " +
                "`contact_name` TEXT, `direction` TEXT NOT NULL, `started_at_epoch_millis` INTEGER NOT NULL, " +
                "`answered_at_epoch_millis` INTEGER, `ended_at_epoch_millis` INTEGER NOT NULL, " +
                "`reason` TEXT NOT NULL, `has_audio` INTEGER NOT NULL, `has_video` INTEGER NOT NULL)"

        /** The indices, also verbatim from `1.json`; Room compares the whole `TableInfo`. */
        val VERSION_1_INDEX_SQL = listOf(
            "CREATE INDEX IF NOT EXISTS `index_call_log_started_at_epoch_millis` " +
                "ON `call_log` (`started_at_epoch_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_call_log_direction_answered_at_epoch_millis` " +
                "ON `call_log` (`direction`, `answered_at_epoch_millis`)",
        )

        /** Also from `1.json`. Room refuses to open a database whose hash it cannot match. */
        const val VERSION_1_IDENTITY_HASH = "f51b000cc7cf1d7b5062dd41fa810e83"
    }
}
