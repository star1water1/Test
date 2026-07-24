plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.novelcharacter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novelcharacter.app"
        minSdk = 26
        targetSdk = 35
        // versionCode는 CI 빌드 번호로 단조 증가(로컬 빌드는 1). 안정 서명키와 함께
        // 덮어쓰기 업데이트가 깨지지 않도록 매 릴리스마다 반드시 올라가야 한다.
        versionCode = 1 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.1"
    }

    // 고정 서명키 — 모든 빌드(디버그 포함)가 항상 같은 인증서로 서명되어야
    // 안드로이드가 기존 설치본 위에 덮어쓰기 업데이트를 허용한다. 키가 빌드마다
    // 달라지면(러너별 자동 생성 debug.keystore) "기존 패키지와 충돌"이 발생한다.
    // 개인 사이드로드 앱이라 키/비밀번호를 저장소에 커밋한다(스토어 배포 시 재고 필요).
    signingConfigs {
        create("shared") {
            storeFile = file("signing/novelcharacter-release.jks")
            storePassword = "novelcharacter"
            keyAlias = "novelcharacter"
            keyPassword = "novelcharacter"
        }
    }

    buildTypes {
        debug {
            // CI가 디버그 APK를 만들므로 디버그도 고정 키로 서명(핵심).
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/**"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Lifecycle (ViewModel + LiveData)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // WorkManager (생일 알림)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Apache POI (엑셀)
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // Android에는 javax.xml.stream (StAX API)이 없어서 명시적으로 추가 필요
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation("com.fasterxml:aalto-xml:1.3.3") // 경량 StAX 구현체 (Android 호환)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp (AI API 호출 — BYOK 프로바이더 공통 HTTP 계층)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson (이미지 경로 JSON 처리)
    implementation("com.google.code.gson:gson:2.11.0")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // MPAndroidChart (성장 곡선 시각화)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ===== 단위 테스트 (순수 JVM — SDK/에뮬레이터/Robolectric 불필요) =====
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
