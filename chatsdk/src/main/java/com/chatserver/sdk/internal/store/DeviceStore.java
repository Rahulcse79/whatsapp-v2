package com.chatserver.sdk.internal.store;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * The one thing the SDK persists on its own: a per-install id.
 *
 * <p>Sent with every connection so the server can tell two installs signed in as
 * the same user apart. Without it both look like one device reconnecting, and the
 * server closes one socket every time the other connects - messages survive that
 * (a resync recovers them) but call invites and live pushes are lost in the gaps.
 * Survives logout deliberately: it identifies the device, not the person.
 */
public final class DeviceStore {

    private static final String PREFS = "chatsdk_device";
    private static final String KEY_INSTALL_ID = "install_id";

    private final SharedPreferences prefs;

    public DeviceStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String installId() {
        String existing = prefs.getString(KEY_INSTALL_ID, null);
        if (existing != null) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply();
        return generated;
    }
}
