# kotlinx.serialization — keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.jaredhq.dashboardandroid.**$$serializer { *; }
-keepclassmembers class dev.jaredhq.dashboardandroid.** {
    *** Companion;
}
