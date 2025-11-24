# Aggressive optimization flags
-optimizationpasses 7
-allowaccessmodification
-dontpreverify
-repackageclasses ''
-overloadaggressively
-mergeinterfacesaggressively

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

# Remove all logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Aggressive Kotlin optimizations
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
    public static void throwNpe(...);
    public static void throwJavaNpe(...);
}

# Remove debug info
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Additional optimizations
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Remove unused resources
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}

# Optimize Compose
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.runtime.**

# Remove BuildConfig debug fields
-assumenosideeffects class **.BuildConfig {
    public static boolean DEBUG;
    public static java.lang.String BUILD_TYPE;
}

# Aggressive string optimizations
-assumenosideeffects class java.lang.String {
    public java.lang.String intern();
}

# Remove verbose exception messages
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}