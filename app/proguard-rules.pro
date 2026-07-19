# Keep the generic signatures and annotations consumed by Room, WorkManager,
# Kotlin serialization, and the Yandex SDK. Their own consumer rules retain
# concrete reflection entry points; Pocket Editor adds no broad class keeps.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# Yandex Auth SDK 3.1.3 references the source-retained Parcelize annotation
# without declaring its marker artifact. It is not read at runtime.
-dontwarn kotlinx.parcelize.Parcelize

# Preserve kotlinx.serialization companions requested by generated serializers.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
