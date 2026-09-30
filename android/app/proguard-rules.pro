# The Anthropic SDK (de)serializes its models with Jackson, which finds constructors, fields and
# annotations by reflection, and uses Kotlin reflection for Kotlin classes. Keep them whole.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisible*Annotations, KotlinMetadata

# Optional dependencies Jackson and OkHttp reference but Android doesn't have.
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**

# Only reached by the SDK's class-based structured-output helpers (schema generation from Java
# types), which Slate doesn't use: it passes its JSON schema directly.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
