import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.signalassist.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.signalassist.app"
        // 공모전 개발 환경 요건: minSdk 28
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // AAOS 차량 API (android.car.*) — 플랫폼 optional 라이브러리
    useLibrary("android.car")

    // 제출용 release 서명: keystore.properties 가 있으면 그 키로, 없으면 debug 키로 서명해 바로 설치 가능하게 한다.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // TODO(Pleos): Pleos Playground 콘솔에서 받은 Vehicle SDK(2.3.x) / NaviHelper(3.0.x) 를 추가하고
    //  vehicle/PleosCabinDataSource.kt, nav/NaviHelperNavigationSource.kt 를 실제 SDK 호출로 채운다.
    // implementation(files("libs/pleos-vehicle-sdk.aar"))
    // implementation(files("libs/pleos-navihelper.aar"))
}
