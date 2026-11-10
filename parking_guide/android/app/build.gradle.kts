plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.parkingguide.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.parkingguide.app"
        minSdk = 28 // Pleos Playground 요구사항: API 28 이상
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 실제 차량(arm64)과 Pleos Connect 에뮬레이터(x86_64)만: OpenCV 네이티브 라이브러리 크기를 줄인다
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Android Automotive 표준 Car API (기어 GEAR_SELECTION, 차속 PERF_VEHICLE_SPEED)
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging { resources.excludes += "META-INF/*.kotlin_module" }
}

dependencies {
    implementation(project(":core"))

    // Pleos Playground SDK (nexus-playground.pleos.ai)
    implementation("ai.pleos.playground:Vehicle:2.3.2")       // SteeringWheel(앞바퀴 조향각)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")  // 음성 안내
    implementation("ai.pleos.playground:FusedLocation:1.1.3") // 지자체 공유용 위치 (약 100m 단위로 반올림)

    // OpenCV Android (core 모듈의 인식기가 쓰는 org.opencv API를 제공)
    implementation("org.opencv:opencv:4.10.0")

    implementation("androidx.core:core-ktx:1.13.1")
}
