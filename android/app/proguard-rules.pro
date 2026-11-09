# SQLCipher uses JNI; keep its classes.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
