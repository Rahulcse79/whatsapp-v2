# PJSIP / pjsua2 (ADR-006).
#
# The stack is reached over JNI, so R8 cannot see the calls that native code makes into
# Java and will otherwise strip or rename the classes and methods the stack depends on.
# The failure mode is a release build that crashes on first use with a NoSuchMethodError,
# or worse, a callback that simply never fires - which is why these rules exist rather
# than being added after a field report.
#
# These rules travel with this module because R8 runs in :app, not here.

# Every pjsua2 type is constructed or reached from C by name, including the SWIG glue
# fields (swigCPtr, swigCMemOwn) the wrappers use to carry the native pointer. The
# `org.pjsip` package also holds the hand-written camera and audio-device shims, which
# pjmedia calls into directly.
-keep class org.pjsip.** { *; }

# Native methods are resolved by name from C, so neither the method nor its owner may be
# renamed.
-keepclasseswithmembernames class * {
    native <methods>;
}

# SWIG directors. `pjsip-apps/src/swig/pjsua2.i` declares these eight classes with
# %feature("director"), which means C++ calls back into a Java SUBCLASS by method name -
# an override R8 renames is a callback that silently never fires. :data:sip subclasses
# Account and Call today; the rest are listed so adding one later is not a field report.
-keep class * extends org.pjsip.pjsua2.Account { *; }
-keep class * extends org.pjsip.pjsua2.Call { *; }
-keep class * extends org.pjsip.pjsua2.Endpoint { *; }
-keep class * extends org.pjsip.pjsua2.LogWriter { *; }
-keep class * extends org.pjsip.pjsua2.Buddy { *; }
-keep class * extends org.pjsip.pjsua2.FindBuddyMatch { *; }
-keep class * extends org.pjsip.pjsua2.AudioMediaPlayer { *; }
-keep class * extends org.pjsip.pjsua2.AudioMediaPort { *; }
