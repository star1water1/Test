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
-keepattributes InnerClasses,EnclosingMethod
-dontwarn com.google.gson.**

# Keep Room entity and DAO classes
-keep class com.novelcharacter.app.data.model.** { *; }
-keep class com.novelcharacter.app.data.dao.** { *; }

# Keep enum types used by Room/Gson
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== App-specific R8 rules =====

# --- Keep ALL app classes (R8이 앱 코드를 제거/이름 변경하는 것을 방지) ---
# 난독화만 비활성화하고 미사용 코드 제거(tree-shaking)는 유지
-keep class com.novelcharacter.app.** { *; }
-keepclassmembers class com.novelcharacter.app.** { *; }

# --- MPAndroidChart (리플렉션 사용) ---
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# --- Gson TypeToken 보존 ---
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# --- WorkManager workers (클래스명으로 인스턴스 생성) ---
-keep class * extends androidx.work.ListenableWorker { *; }

# --- Kotlin metadata & coroutines ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
