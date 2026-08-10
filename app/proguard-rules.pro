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
#
# ⚠️ **이 두 줄을 좁히려는 사람에게 (백로그 B-125 · 확정 13-5 ⓑ).**
# `.github/workflows/build-apk.yml`의 `Build Release APK`는 **수동 실행 입력**
# (`build_type == 'release'`)에만 걸려 있어 **push에서도 PR에서도 돌지 않는다.** 즉
# **R8이 도는 경로를 CI가 한 번도 밟은 적이 없다.** 지금 그것이 안전한 이유는 오직 아래
# 두 줄이다 — 앱 패키지를 통째로 보존해 R8이 앱 코드를 건드리지 않으므로, 리플렉션 대상
# 소실·keep 규칙 누락 같은 **릴리스에서만 깨지는 부류**가 발생할 자리 자체가 없다.
#
# **좁히는 순간 그 사각이 살아난다.** 사용자 확정(2026.08.10)은 갈래 ⓑ였다 —
# *"CI에 릴리스 조립을 상시로 더하지 않고, **keep 규칙을 좁히는 슬라이스가 릴리스 빌드
# 증명을 함께 진다**"*. 그래서 **이 두 줄을 좁히는 변경의 완료 조건은 릴리스 조립을 실제로
# 돌려 초록을 보는 것**이다(수동 실행 `build_type=release` 또는 워크플로 조건 확장).
# 그 조건을 빼먹으면 확정이 *"안 하기로 했다"*로 퇴화한다 — **경고를 백로그가 아니라 이
# 자리에 두는 이유가 그것이다.** 백로그 행은 좁히려는 사람이 읽지 않지만 이 줄은 읽는다.
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
