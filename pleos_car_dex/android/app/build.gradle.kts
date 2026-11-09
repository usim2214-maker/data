plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pleos.cardex"
    compileSdk = 35
    useLibrary("android.car") // CarUxRestrictionsManager (주행 중 UX 제한)

    defaultConfig {
        applicationId = "com.pleos.cardex"
        minSdk = 28 // Pleos Connect SDK 최소 요구 (Android Automotive OS 기반)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 백엔드 주소. 에뮬레이터 -> 호스트 PC = 10.0.2.2
        // 다른 주소로 빌드: ./gradlew assembleDebug -PapiBaseUrl=http://192.168.0.10:8000/
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // true: Pleos SDK 를 쓰지 않고 목업 위치로 동작 (false 여도 isSupported()=false 면 자동으로 목업 사용)
        buildConfigField("boolean", "USE_MOCK_PLEOS", "false")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2") }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 화면 스크린샷 테스트 (JVM 에서 Compose 렌더링): ./gradlew testDebugUnitTest --tests '*Screenshot*'
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ---- Pleos Playground SDK ----
    implementation("ai.pleos.playground:FusedLocation:1.1.3") // 위치·근접 알림 (지역/히든 스탬프)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")    // AI 코스 -> Pleos 내비 길안내
    implementation("ai.pleos.playground:Vehicle:2.3.2")       // 기어(P단) -> 주행 중 화면 잠금
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")  // 주행 중 음성 안내
    // TODO(Pleos): 서라운드뷰 등 차량 영상 API 공개 시 VideoFrameSource 를 대체
}
