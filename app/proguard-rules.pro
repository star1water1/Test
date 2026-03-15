# ProGuard/R8 configuration for NovelCharacter app

# Keep Apache POI classes
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.commons.**
-dontwarn javax.xml.**

# Keep Gson serialization/deserialization
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# Keep Room entity and DAO classes
-keep class com.novelcharacter.app.data.model.** { *; }
-keep class com.novelcharacter.app.data.dao.** { *; }

# Keep enum types used by Room/Gson
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
