-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Preserve AIDL IPC interfaces, stubs, and proxies
-keep class * implements android.os.IInterface { *; }
-keep interface * extends android.os.IInterface { *; }
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve Lyricon library classes, interfaces, and models for cross-process IPC
-keep class io.github.proify.lyricon.** { *; }
-dontwarn io.github.proify.lyricon.**
-keep class * implements io.github.proify.lyricon.subscriber.ActivePlayerListener { *; }
-keep class * implements io.github.proify.lyricon.subscriber.ConnectionListener { *; }
