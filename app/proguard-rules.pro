# ProGuard rules for Vauchi

# Keep UniFFI generated classes
-keep class uniffi.** { *; }
-keep class com.vauchi.uniffi.** { *; }

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
