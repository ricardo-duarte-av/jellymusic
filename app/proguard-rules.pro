# ---- kotlinx.serialization (the Jellyfin SDK models are all @Serializable) ----
# The serialization Gradle plugin ships most rules, but keep the generated serializers and
# the Jellyfin model classes explicitly since they are (de)serialized reflectively by name.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**

-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class **$$serializer { *; }

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Jellyfin SDK models + their generated serializers.
-keep,includedescriptorclasses class org.jellyfin.sdk.model.** { *; }
-keep class org.jellyfin.sdk.**$$serializer { *; }

# ---- Media3 ----
-keep class androidx.media3.** { *; }

# ---- slf4j (optional backend resolved via ServiceLoader; silence missing-class notes) ----
-dontwarn org.slf4j.**

# ---- Coil (network fetcher registered via ServiceLoader) ----
-keep class coil3.util.** { *; }
