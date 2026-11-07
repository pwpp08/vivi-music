# kotlinx.serialization: mantieni i serializer generati.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.xlollx.songport.**$$serializer { *; }
-keepclassmembers class com.xlollx.songport.** { *** Companion; }
-keepclasseswithmembers class com.xlollx.songport.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WebView: i metodi annotati @JavascriptInterface (login Apple Music) vengono chiamati per nome
# dal JavaScript della pagina; se R8 li rinomina il login si rompe solo in release.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Tink (dentro androidx.security:security-crypto) cita annotazioni Error Prone che servono solo a
# compilare e non arrivano a runtime. Nella build Play le portava per caso l'SDK degli annunci; senza
# di esso R8 si ferma su "Missing class". Sono solo annotazioni: si possono ignorare.
-dontwarn com.google.errorprone.annotations.**
