# kotlinx.serialization keeps generated serializers through @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class lol.mony.harmony.**$$serializer { *; }
-keepclassmembers class lol.mony.harmony.** { *** Companion; }
-keepclasseswithmembers class lol.mony.harmony.** { kotlinx.serialization.KSerializer serializer(...); }

# LiveKit references okhttp3.internal.Util, which OkHttp 5 no longer ships under that
# name; the reference is inside a Throwable handler and is never loaded. R8 treats a
# missing class as an error under the release build's minification, so it is named here
# as absent rather than kept: keeping it would mean pinning OkHttp 4.
-dontwarn okhttp3.internal.Util
