# Navigation type-safe routes are @Serializable classes; keep their generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class app.facet.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
