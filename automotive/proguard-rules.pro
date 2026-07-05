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
