# Applied automatically to any app that depends on this AAR.
# The wire payloads are populated by Gson through reflection - stripping or
# renaming their fields would silently break every frame.
-keep class com.chatserver.sdk.internal.ws.payload.** { *; }
-keep class com.chatserver.sdk.internal.net.dto.** { *; }
-keep class com.chatserver.sdk.internal.ws.Frame { *; }
-keep class com.chatserver.sdk.internal.ws.IncomingFrame { *; }
# The public surface a host codes against.
-keep public class com.chatserver.sdk.** { public *; }
