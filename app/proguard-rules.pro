# Raven ProGuard / R8 keep rules
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

# Preserve kotlinx.serialization companion & generated serializers (domain models + RavenRoute)
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.raven.app.core.model.** { *; }

# Keep Koin ViewModel & DI definitions
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
