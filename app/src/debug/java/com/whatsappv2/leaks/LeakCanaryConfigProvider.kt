package com.whatsappv2.leaks

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers
import shark.IgnoredReferenceMatcher
import shark.ReferencePattern

/**
 * Tells LeakCanary about the one retention this app cannot fix (Task 65, DoD 16).
 *
 * After every call LeakCanary reports `SipConnectionService` leaked through
 * `android.telecom.ConnectionService$1.this$0`, GC root *"Global variable in native
 * code"*, retaining 2.7 kB. `ConnectionService$1` is the framework's own
 * `IConnectionService.Stub` for the service; Telecom holds its binder through a JNI
 * global reference from the system process after `unbindService`, until the system
 * drops it. The service's earlier, real leak — holding itself as every connection's
 * listener — is fixed and documented on `SipConnectionService`; what is left is the
 * framework's reference, which no code in this process can release and which the same
 * report showed at 2.7 kB.
 *
 * Ignored by pattern rather than by silencing LeakCanary: any *other* path to a retained
 * service, or a retained activity, still fires. The matcher names the exact field.
 *
 * A `ContentProvider` because LeakCanary's own installer is one, and every provider runs
 * before `Application.onCreate` — so the configuration is in place before the first heap
 * analysis, without the debug variant needing its own `Application` subclass.
 */
class LeakCanaryConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = AndroidReferenceMatchers.appDefaults +
                IgnoredReferenceMatcher(
                    ReferencePattern.InstanceFieldPattern("android.telecom.ConnectionService$1", "this$0"),
                ),
        )
        return true
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
}
