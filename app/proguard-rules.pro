# Netty 在 Android 上的可选依赖裁剪
-dontwarn io.netty.**
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.bilicraft.handheld.**$$serializer { *; }
-keepclassmembers class com.bilicraft.handheld.** {
    *** Companion;
}
-keepclasseswithmembers class com.bilicraft.handheld.** {
    kotlinx.serialization.KSerializer serializer(...);
}