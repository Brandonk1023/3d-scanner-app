# JNI entry points are looked up by name from C++; keep the bridge class and its native methods.
-keep class app.facet.nativecore.FacetCoreJni { native <methods>; }
