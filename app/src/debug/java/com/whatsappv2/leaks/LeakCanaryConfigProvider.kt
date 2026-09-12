package com.whatsappv2.leaks

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers
import shark.LibraryLeakReferenceMatcher
import shark.ReferencePattern

/**
 * Tells LeakCanary about the one retention this app cannot fix (Task 65, DoD 16).
 *
 * After a call, LeakCanary reports `SipConnectionService` retained through
 * `android.telecom.ConnectionService$N.this$0`, GC root *"Global variable in native
 * code"*, holding 2.7 kB in 38 objects. That anonymous class is the framework's own
 * `RemoteServiceCallback.Stub`; the system process holds its binder through a JNI global
 * reference after `unbindService` until it drops it, and no code in this process can
 * release it. The service's earlier, *real* leak — it held itself as every connection's
 * listener, and connections live in a static map — is fixed and documented on
 * `SipConnectionService`.
 *
 * ## The index is not stable, and pinning it is what went wrong
 *
 * This used to name `ConnectionService$1`, which is what the framework on the handset of
 * the day happened to number it. On the Zebra TC15 (Android 13, SDK 33) the same
 * retention arrives as **`ConnectionService$5`** — anonymous-class numbering is a property
 * of the platform build, not of this app — so the matcher missed and every call reported
 * "1 APPLICATION LEAKS" for a retention that was already understood and already written
 * down here. That is worse than not having a matcher at all: a leak report that is always
 * wrong is a leak report nobody reads, and the next *real* leak arrives in the same
 * sentence. So the pattern now covers the whole anonymous range instead of one index.
 *
 * Over-matching costs nothing that matters. `this$0` on an anonymous class of
 * `android.telecom.ConnectionService` can only ever point at the service instance, which
 * is precisely the retention being classified. Every other path still fires: a retained
 * `CallActivity`, or a service held by this app's own code — the old listener leak ran
 * through the static connection map, not through the framework's stub, so it would be
 * reported today exactly as it was then.
 *
 * ## A library leak, not an ignore
 *
 * `LibraryLeakReferenceMatcher` rather than `IgnoredReferenceMatcher`: the retention is
 * still printed, under LIBRARY LEAKS, with the explanation below attached to it. The
 * application-leak count — the number a sweep actually gates on — goes to zero, and the
 * knowledge stays where the next person to read a heap analysis will find it, instead of
 * being deleted from the output.
 *
 * ## Heap dumps are capped, and why the cap alone is not enough
 *
 * Every analysis keeps its `.hprof` — 40 MB each on this app — and LeakCanary writes them
 * to the phone's shared `Download/leakcanary-<package>/`, not to the app's own files. On
 * Android 11+ a file there belongs to the UID that created it, and an uninstall does not
 * take it along: a reinstalled app is a new UID that can neither delete the old pile nor
 * count it against `maxStoredHeapDumps`. One test phone reached 21 dumps and 859 MB that
 * way, every one of them orphaned. So the cap below keeps *this* install's dumps to the
 * two most recent, and `./launch.sh --reinstall` clears the directory before the uninstall
 * that would otherwise orphan it — the only moment anything can.
 *
 * A `ContentProvider` because LeakCanary's own installer is one, and every provider runs
 * before `Application.onCreate` — so the configuration is in place before the first heap
 * analysis, without the debug variant needing its own `Application` subclass.
 */
class LeakCanaryConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = AndroidReferenceMatchers.appDefaults + connectionServiceStubMatchers(),
            maxStoredHeapDumps = MAX_STORED_HEAP_DUMPS,
        )
        return true
    }

    /**
     * One matcher per plausible anonymous class of `android.telecom.ConnectionService`.
     *
     * Enumerated because `shark`'s patterns match an exact class name — there is no
     * wildcard — and the index varies by platform build. The bound is generous on purpose:
     * an unused matcher costs one string comparison per reference, and an index this
     * misses is the whole defect being fixed here coming back.
     */
    private fun connectionServiceStubMatchers() = (1..ANONYMOUS_CLASS_BOUND).map { index ->
        LibraryLeakReferenceMatcher(
            pattern = ReferencePattern.InstanceFieldPattern(
                "android.telecom.ConnectionService\$$index",
                "this\$0",
            ),
            description = "The system process holds the framework's RemoteServiceCallback binder " +
                "stub through a JNI global reference after unbindService, which keeps the " +
                "ConnectionService instance reachable. Nothing in this process can release it. " +
                "See LeakCanaryConfigProvider.",
        )
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0

    private companion object {
        /** Android 13 numbers the stub `$5`; the range covers the builds either side of it. */
        const val ANONYMOUS_CLASS_BOUND = 20

        /**
         * The analysis is what is read; the dump is only needed to re-run it. Two is the
         * one being looked at and the one before it, which is as far back as anyone has
         * ever compared — at ~40 MB a dump, the default of seven was 280 MB of a test
         * phone for nothing.
         */
        const val MAX_STORED_HEAP_DUMPS = 2
    }
}
