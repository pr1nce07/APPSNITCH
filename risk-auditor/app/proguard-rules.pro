# kotlinx.serialization keeps generated serializers on the companion object.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.riskauditor.** {
    *** Companion;
}
-keepclasseswithmembers class com.riskauditor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.riskauditor.**$$serializer { *; }

# OkHttp / Okio ship their own rules; these silence platform-only references.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
