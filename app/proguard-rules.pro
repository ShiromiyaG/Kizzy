# Optimization flags
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''

#OkHttp Rules
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Ktor Rules
-keepclassmembers class io.ktor.http.** { *; }

# Keep Domain data classes
-keep class com.my.kizzy.domain.model.** { <fields>; }

# Keep Data data classes
-keep class com.my.kizzy.data.remote.** { <fields>; }

# Keep Gateway data classes
-keep class kizzy.gateway.entities.** { <fields>; }

# slf4j error during build
-dontwarn org.slf4j.impl.StaticLoggerBinder

# some unknown error
-dontwarn java.lang.invoke.StringConcatFactory

-dontwarn com.my.kizzy.resources.R$drawable

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Kotlin optimizations
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
}

# Remove debug info
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile