# ==============================================================================
# Raven (渡鸦) ProGuard / R8 Keep Rules
# ==============================================================================

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable
-dontnote kotlinx.serialization.AnnotationsKt

# ------------------------------------------------------------------------------
# kotlinx.serialization (all @Serializable models, RavenRoute, DTOs, backup/cache)
# ------------------------------------------------------------------------------
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.raven.app.**$$serializer { *; }
-keepclassmembers class com.raven.app.** {
    *** Companion;
}
-keep @kotlinx.serialization.Serializable class com.raven.app.** { *; }
-keep class com.raven.app.core.model.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------------------
# Room (@Database, @Dao, @Entity, generated _Impl classes)
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.raven.app.core.database.**_Impl { *; }
-keep @androidx.room.Entity class com.raven.app.core.database.** { *; }
-keep @androidx.room.Dao interface com.raven.app.core.database.** { *; }

# ------------------------------------------------------------------------------
# Koin DI, ViewModels, SitePlugin SPI & implementations
# ------------------------------------------------------------------------------
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * implements com.raven.app.core.sitespi.SitePlugin { *; }
-keep class com.raven.app.core.sitespi.** { *; }
-keep class org.koin.** { *; }

# ------------------------------------------------------------------------------
# OkHttp / Okio / Jsoup / Coil 3 / Media3
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jsoup.**

-keep class org.jsoup.nodes.** { *; }
-keep class org.jsoup.select.** { *; }
-keep class androidx.media3.** { *; }
-keep class coil3.** { *; }
