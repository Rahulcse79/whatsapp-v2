# liblinphone (ADR-001).
#
# The SDK is reached over JNI, so R8 cannot see the calls from native code into Java and
# will otherwise strip or rename the classes the stack depends on. The failure mode is a
# release build that crashes on first use with a NoSuchMethodError - which is why these
# rules exist rather than being added after a field report.

-keep class org.linphone.core.** { *; }
-keep class org.linphone.mediastream.** { *; }

# Native methods are resolved by name from C, so neither the method nor its owner may be
# renamed.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The stack instantiates listeners reflectively.
-keep class * implements org.linphone.core.CoreListener { *; }
