# Add project specific ProGuard rules here.

# Keep data model classes (used by Gson reflection and Room)
-keep class com.novelcharacter.app.data.model.** { *; }

# Keep Gson TypeToken and related reflection
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep Apache POI classes
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.commons.**
-dontwarn javax.xml.**
