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

# ---- Strip debug/verbose logging from release ----
# Diagnostic Log.d/Log.v calls (e.g. the Android Auto browse tracing) have no side effects, so R8
# can remove the calls entirely in release. Argument-building code (string templates) that only feeds
# these calls then becomes dead and is dropped too. Log.i/w/e are kept.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ---- slf4j (optional backend resolved via ServiceLoader; silence missing-class notes) ----
-dontwarn org.slf4j.**

# ---- Coil (network fetcher registered via ServiceLoader) ----
-keep class coil3.util.** { *; }

# ---- Glance home-screen widget ----
# Glance does not retain ActionCallback instances: on a button tap it reads the class name out of
# the PendingIntent and instantiates it reflectively via
# Class.forName(name).getDeclaredConstructor().newInstance(). The `T::class.java` at the call site
# keeps the class body, but R8 still strips the (only-reflectively-used) no-arg constructor, so in
# release every widget button throws inside Glance's dispatch and silently does nothing — while
# debug (no R8) works. Keep the callbacks and their constructors intact and unobfuscated.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Keep the widget + its receiver un-shrunk/un-renamed so updateAll() resolves and re-renders the
# right provider when PlaybackService mirrors player state into the widget's DataStore.
-keep class pt.aguiarvieira.jellymusic.widget.** { *; }
