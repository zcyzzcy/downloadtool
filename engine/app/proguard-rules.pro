# youtubedl-android 库走反射/动态加载，整体保留，防止 R8 误删
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
# JS 桥方法必须保留（R8 会改名导致网页调用失效）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# jackson 引用的 JDK 桌面类在 Android 上不存在（R8 报 missing class 偶发炸构建）
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
