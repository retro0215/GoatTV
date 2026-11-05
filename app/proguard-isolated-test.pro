# Optimized isolated test APK only (`-Powntv.isolatedTestOptimized=true`, build type tutorialTest).
# Pushwoosh and Firebase are left out of this build's runtime classpath (app/build.gradle.kts), while the
# brand source sets' PushNotifications still compile against them; that code is never called here
# (PUSHWOOSH_ENABLED = false). Tell R8 the classes are absent on purpose rather than failing the build.
-dontwarn com.pushwoosh.**
-dontwarn com.google.firebase.**
-dontwarn com.google.android.datatransport.**
