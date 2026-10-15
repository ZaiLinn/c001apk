# kotlinx.serialization: keep generated serializers of app and core models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.zailinn.c001apk.** {
    *** Companion;
}
-keepclasseswithmembers class com.zailinn.c001apk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# jBCrypt and jsoup are reflection-free; OkHttp ships its own rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
