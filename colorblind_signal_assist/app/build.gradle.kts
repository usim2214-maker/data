plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.signalassist.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.signalassist.app"
        // Pleos Connect 는 최신 AAOS 기반. Car.createCar(..., CarServiceLifecycleListener) 가 API 30+ 이라 31 로 둔다.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // AAOS 차량 API (android.car.*) — 플랫폼 optional 라이브러리
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // TODO(Pleos): Pleos Playground 에서 받은 Pleos Connect / Vehicle SDK 를 여기에 추가하고
    //  vehicle/PleosVehicleDataSource.kt 를 실제 SDK 호출로 채운다.
    // implementation(files("libs/pleos-vehicle-sdk.aar"))
}
