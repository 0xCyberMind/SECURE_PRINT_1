# PrivPrint Production ProGuard / R8 Rules

# Preserve Kotlin reflection & line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Moshi & API Data Models
-keep class com.example.privprint.data.api.models.** { *; }
-keep class com.example.privprint.data.model.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Google Tink & Cryptography
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
