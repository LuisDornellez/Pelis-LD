# Atributos necesarios para reflexión y anotaciones
-keepattributes *Annotation*, JavascriptInterface, Signature, InnerClasses, EnclosingMethod

# Puente JavaScript <-> Kotlin (clase de nivel superior)
-keep class com.pelisld.app.MediaJavaScriptInterface { *; }
-keepclassmembers class com.pelisld.app.MediaJavaScriptInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Variante por si la clase vuelve a declararse como interna de MainActivity
-keep class com.pelisld.app.MainActivity$MediaJavaScriptInterface { *; }
-keepclassmembers class com.pelisld.app.MainActivity$MediaJavaScriptInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Cualquier método anotado con @JavascriptInterface en cualquier clase
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Modelo de datos
-keep class com.pelisld.app.MediaItem { *; }

# Librerías de red
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# RTMP (ExoPlayer extension + librtmp JNI)
-keep class com.google.android.exoplayer2.ext.rtmp.** { *; }
-keep class io.antmedia.rtmp_client.** { *; }
