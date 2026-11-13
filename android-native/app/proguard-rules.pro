# kotlinx.serialization keeps generated serializers through @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class lol.mony.harmony.**$$serializer { *; }
-keepclassmembers class lol.mony.harmony.** { *** Companion; }
-keepclasseswithmembers class lol.mony.harmony.** { kotlinx.serialization.KSerializer serializer(...); }
