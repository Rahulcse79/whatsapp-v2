# R8 rules for the application (Task 64, §7, DoD 1).
#
# The SIP SDK's own keeps travel with :data:sip's consumer-rules.pro, so they are not
# repeated here: a rule copied into two files is a rule that gets updated in one.
#
# Everything below is about THIS module. The bar for adding a rule is a demonstrated
# failure, not a precaution — a `-keep class com.whatsappv2.**` would make the whole
# exercise pointless while looking responsible.

# ---------------------------------------------------------------- crash reports
# Line numbers, so a stack trace from a release build can be deobfuscated with the
# mapping file CI archives. Without SourceFile the trace has no line numbers at all;
# without the rename it leaks the original file names the rest of this is hiding.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- reflection
# Kotlin metadata, read at runtime by kotlin-reflect and by Kotlin's own intrinsics for
# things like `data class` copy on a serialized type.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod

# ---------------------------------------------------------------- Firebase
# The messaging service is resolved from the manifest by name, so the class may not be
# renamed. Its methods may: nothing calls them reflectively.
-keep class com.whatsappv2.push.SipMessagingService

# ---------------------------------------------------------------- Telecom
# ConnectionService and its BroadcastReceiver are instantiated by the platform from the
# manifest. Same reasoning as above: the names are the contract.
-keep class com.whatsappv2.telecom.SipConnectionService
-keep class com.whatsappv2.call.CallActionReceiver
-keep class com.whatsappv2.service.RegistrationService

# ---------------------------------------------------------------- noise
# Coroutines ships a debug agent probe that R8 warns about and nothing here uses.
-dontwarn kotlinx.coroutines.debug.**
