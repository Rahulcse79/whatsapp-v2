package com.whatsappv2.data.account.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stored SIP account: every §5.1 field, flattened for SQLite.
 *
 * ## What is encrypted
 *
 * [passwordCiphertext] and [turnPasswordCiphertext] hold the output of
 * `CredentialCipher`, never a plaintext password. They are named `...Ciphertext` rather
 * than `password` so that a future `SELECT` written in a hurry cannot read as though it
 * returns something usable, and so a plaintext assignment looks wrong at the call site.
 *
 * Keeping them in this row rather than in a separate encrypted file is deliberate: an
 * account and its credential are written, migrated and deleted in one transaction, so
 * they cannot drift apart — including the case where deleting an account leaves its
 * password behind.
 *
 * ## What is NOT stored
 *
 * Registration status. It is derived, changes many times a second during a retry storm,
 * and a stale copy in the database is exactly the "shows Registered while the transport
 * is down" failure §6 forbids. It lives in `SipEngine.registrationState`.
 *
 * ## Shape
 *
 * Optional values are nullable rather than empty strings, matching the domain entity, so
 * "cleared" and "never set" do not become the same state on the way to disk.
 *
 * Structured values (host:port, codec lists) are stored in their rendered text form and
 * re-parsed on the way out. SQLite has no type for them, and a parse on read means a
 * corrupt value is caught at the boundary rather than propagating into a SIP request.
 */
@Entity(
    tableName = "sip_accounts",
    indices = [
        // One registration per identity: two accounts for the same user@domain would
        // fight over the same binding on the registrar (Task 22).
        Index(value = ["username", "domain"], unique = true),
        Index(value = ["is_default"]),
    ],
)
data class SipAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "extension")
    val extension: String?,

    @ColumnInfo(name = "auth_username")
    val authUsername: String?,

    /** Ciphertext from `CredentialCipher`. Never a plaintext password. */
    @ColumnInfo(name = "password_ciphertext")
    val passwordCiphertext: String,

    @ColumnInfo(name = "display_name")
    val displayName: String?,

    @ColumnInfo(name = "domain")
    val domain: String,

    /** Rendered `host[:port]`, or null to use [domain]. */
    @ColumnInfo(name = "registrar")
    val registrar: String?,

    @ColumnInfo(name = "outbound_proxy")
    val outboundProxy: String?,

    /** Null means "use the transport default" — 5060, or 5061 for TLS. */
    @ColumnInfo(name = "port")
    val port: Int?,

    @ColumnInfo(name = "transport")
    val transport: String,

    @ColumnInfo(name = "registration_expiry_seconds")
    val registrationExpirySeconds: Int,

    @ColumnInfo(name = "stun_server")
    val stunServer: String?,

    @ColumnInfo(name = "turn_server")
    val turnServer: String?,

    @ColumnInfo(name = "turn_username")
    val turnUsername: String?,

    /** Ciphertext, on the same terms as [passwordCiphertext]. */
    @ColumnInfo(name = "turn_password_ciphertext")
    val turnPasswordCiphertext: String?,

    @ColumnInfo(name = "ice_enabled")
    val iceEnabled: Boolean,

    @ColumnInfo(name = "stun_enabled")
    val stunEnabled: Boolean,

    @ColumnInfo(name = "keepalive_interval_seconds")
    val keepaliveIntervalSeconds: Int,

    @ColumnInfo(name = "srtp_policy")
    val srtpPolicy: String,

    /** Comma-separated, in SDP offer order. Order is meaningful, so this is a list. */
    @ColumnInfo(name = "audio_codecs")
    val audioCodecs: String,

    /** Comma-separated. Empty means the account is audio-only. */
    @ColumnInfo(name = "video_codecs")
    val videoCodecs: String,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,

    /** Creation time, so the account list has a stable order that is not the id. */
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)
