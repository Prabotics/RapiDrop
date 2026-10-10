-keepattributes *Annotation*
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class com.prabotics.rapidrop.clipboard.ConnectedDeviceInfo { *; }
-keep class com.prabotics.rapidrop.clipboard.ClipItem { *; }
-keep class com.prabotics.rapidrop.clipboard.ClipContentType { *; }
-keep class com.prabotics.rapidrop.network.PairInviteInfo { *; }
-keep class com.prabotics.rapidrop.network.DiscoveredDevice { *; }
-keep class com.prabotics.rapidrop.network.WireFrame { *; }
-keep class com.prabotics.rapidrop.network.PacketType { *; }
-keep class com.prabotics.rapidrop.security.** { *; }
-keep class com.prabotics.rapidrop.service.TransferProgress { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int v(...);
}
