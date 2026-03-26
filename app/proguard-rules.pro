# ProGuard rules for Vauchi

# Keep UniFFI generated classes
-keep class uniffi.** { *; }
-keep class app.vauchi.uniffi.** { *; }

# Keep JNA classes
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# JNA uses AWT classes that don't exist on Android - ignore them
-dontwarn java.awt.**

# Keep native library loading
-keepclassmembers class * {
    native <methods>;
}

# Strip debug and verbose log calls from release builds.
# Log.i/Log.w/Log.e are kept — they indicate startup or actionable errors.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
