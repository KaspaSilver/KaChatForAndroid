# KaChat ProGuard rules

# Keep data classes used with Gson serialization (Retrofit DTOs)
-keepclassmembers class com.kachat.app.services.** { *; }
-keepclassmembers class com.kachat.app.models.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# gRPC
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Room
-keep class androidx.room.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
