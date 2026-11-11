# ReelGuard ProGuard Rules

# -------------------------
# AccessibilityService
# -------------------------
-keep public class * extends android.accessibilityservice.AccessibilityService {
    public <methods>;
}
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public <methods>;
}

# -------------------------
# WorkManager Workers
# -------------------------
-keep public class * extends androidx.work.Worker
-keep public class * extends androidx.work.CoroutineWorker
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# -------------------------
# BroadcastReceiver
# -------------------------
-keep public class * extends android.content.BroadcastReceiver {
    public <methods>;
}

# -------------------------
# Security Crypto (Tink / EncryptedSharedPreferences)
# -------------------------
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# -------------------------
# General Android keep rules
# -------------------------
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R class fields referenced by reflection
-keepclassmembers class **.R$* {
    public static <fields>;
}
