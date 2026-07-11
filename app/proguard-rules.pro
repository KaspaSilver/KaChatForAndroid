# KaChat ProGuard rules

# Keep data classes used with Gson serialization (Retrofit DTOs)
-keepclassmembers class com.kachat.app.services.** { *; }
-keepclassmembers class com.kachat.app.models.** { *; }

# Gson: retain generic signatures of TypeToken and its (usually anonymous, e.g.
# `object : TypeToken<List<Account>>() {}`) subclasses. Without this, R8 can merge/strip those
# synthetic subclasses and drop the generic signature Gson reads at runtime, throwing
# "TypeToken must be created with a type argument" the moment any Gson-backed store (accounts,
# pending KNS commits, etc.) is read — confirmed via device logcat: WalletManager.createWallet()
# -> setActiveAccount() -> getAccounts() crashes on this exact line the instant an account is
# first saved, then every subsequent launch crash-loops on the same read at Application.onCreate.
# Only ever showed up in release builds since debug has isMinifyEnabled = false and R8 never
# runs there. Rule per Gson's own recommendation for R8 3.0+.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

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
