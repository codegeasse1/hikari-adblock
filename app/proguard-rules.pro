# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number info for debugging crashes
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlin.reflect.jvm.internal.** { *; }

# Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# Keep data classes used by Room
-keep class com.codegeasse1.hikariadblock.data.** { *; }

# Keep VPN service
-keep class com.codegeasse1.hikariadblock.service.** { *; }

# Shizuku API
# The provider/api classes register themselves in the manifest and pass
# Parcelables across the Shizuku server (shell/root) boundary by class name,
# so every entry point must survive R8 untouched. Missing the moe.shizuku /
# rikka.sui keeps is a classic "works in debug, breaks in release" trap.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-dontwarn rikka.sui.**
# BinderContainer is written into a Bundle by the Shizuku server and read back
# here by class name (Bundle#getParcelable), so it must keep its name + CREATOR.
-keep class moe.shizuku.api.BinderContainer { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepattributes *Annotation*, InnerClasses, Signature

# Go tunnel (gomobile)
-keep class tunnel.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

-assumenosideeffects class org.slf4j.Logger {
    public *** debug(...);
    public *** info(...);
    public *** trace(...);
}
