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

# ── Vendored IDO/VeryFit watch SDK (ADR 0001) ────────────────────────────────
# The native lib (libVeryFitMulti.so) binds to the EXACT class+method names of the
# JNI boundary and calls BACK into fixed-name Java statics (callbacks). Renaming or
# stripping any of these silently breaks the protocol at runtime, so keep the whole
# SDK surface verbatim. Minify is off today; these rules make it safe to enable later.
-keep class com.veryfit.multi.** { *; }
-keep class com.ido.ble.** { *; }
# greenDAO entities/DAOs are reflected over by the ORM; gson reflects over model fields.
-keep class com.ido.ble.data.manage.database.** { *; }
-keep class org.greenrobot.greendao.** { *; }
-keep class * extends org.greenrobot.greendao.AbstractDao { *; }
-keepclassmembers class * extends org.greenrobot.greendao.AbstractDao { *; }
-keepattributes Signature, *Annotation*
# The SDK references DFU/watch-face deps (com.sifli.*, com.realsil.*, javelin.*) that
# are NOT vendored for the health-first build; health code paths never load them, but
# silence the build-time "missing class" warnings so the dex step doesn't get noisy.
-dontwarn com.sifli.**
-dontwarn com.realsil.**
-dontwarn javelin.**
-dontwarn com.ido.ble.**
