# ProGuard/R8 configuration for NovelCharacter app

# Keep Apache POI classes
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.xmlbeans.**
-dontwarn javax.xml.**

# Keep StAX API and Aalto XML (Android에서 POI OOXML 동작에 필요)
-keep class javax.xml.stream.** { *; }
-keep class com.fasterxml.aalto.** { *; }
-dontwarn com.fasterxml.aalto.**

# Keep Apache POI service provider implementations (loaded via reflection)
-keep class org.apache.poi.sl.draw.** { *; }
-keep class org.apache.poi.ss.formula.functions.** { *; }
-keepclassmembers class org.apache.poi.** {
    public <init>(...);
}
-dontwarn org.apache.batik.**
-dontwarn org.apache.logging.**
-dontwarn org.apache.log4j.**
-dontwarn org.slf4j.**
-dontwarn de.rototor.pdfbox.**
-dontwarn org.openxmlformats.**
-keep class org.openxmlformats.** { *; }

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
