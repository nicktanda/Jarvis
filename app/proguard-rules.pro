# Keep serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.adam.app.**$$serializer { *; }
-keepclassmembers class com.adam.app.** { *** Companion; }
-keepclasseswithmembers class com.adam.app.** { kotlinx.serialization.KSerializer serializer(...); }
