# Jellyfin SDK uses kotlinx.serialization; keep serializers.
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class org.jellyfin.sdk.model.api.** { *; }

# Media3
-keep class androidx.media3.** { *; }
