# Keep serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jarvis.app.**$$serializer { *; }
-keepclassmembers class com.jarvis.app.** { *** Companion; }
-keepclasseswithmembers class com.jarvis.app.** { kotlinx.serialization.KSerializer serializer(...); }
