# Live AI Reply - ProGuard / R8 rules

# OkHttp uses platform-specific optional dependencies that are not present on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines ship optional service loaders.
-dontwarn kotlinx.coroutines.**

# ML Kit text recognition is referenced reflectively in places.
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }

# Keep the accessibility service entry point (instantiated by the framework).
-keep class com.liveaireply.app.accessibility.LiveReplyAccessibilityService { *; }

# Keep notification action receivers (referenced by PendingIntent).
-keep class com.liveaireply.app.notifications.AssistantActionReceiver { *; }

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
