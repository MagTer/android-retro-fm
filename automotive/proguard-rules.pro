# Keep generated kotlinx.serialization serializers for our DTOs so R8 can still
# parse API responses in a minified release build.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.retrofm.android.**$$serializer { *; }
-keepclassmembers class com.retrofm.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.retrofm.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The car artifact excludes the GMS/Cast dependency (see build.gradle.kts): media3-cast's
# CastPlayer classes stay on the classpath but reference GMS types that are no longer packaged.
# The cast code path is never reached on FEATURE_AUTOMOTIVE, so let R8 tolerate the dangling
# references instead of failing the build.
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.datatransport.**
-dontwarn androidx.media3.cast.**
