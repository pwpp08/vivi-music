# Serializers of the connectors' data classes, called by name by kotlinx.serialization.
-keep,includedescriptorclasses class com.xlollx.songport.ytmbridge.**$$serializer { *; }
-keepclassmembers class com.xlollx.songport.ytmbridge.** { *** Companion; }
-keepclasseswithmembers class com.xlollx.songport.ytmbridge.** { kotlinx.serialization.KSerializer serializer(...); }
# The web players call these by name from JavaScript.
-keepclassmembers class com.xlollx.songport.ytmbridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
