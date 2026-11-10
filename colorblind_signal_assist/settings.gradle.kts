pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ColorblindSignalAssist"

// 순수 Kotlin 모듈: 신호 인식 / 상태 추적 / 알림 정책 / 합성 영상 (Android SDK 없이 빌드·테스트 가능)
include(":core")
// PC에서 시나리오를 돌려보고 합성 프레임 PNG를 뽑는 데모
include(":demo")

// Android Automotive(Pleos Connect) 앱 모듈은 Android SDK가 있을 때만 포함
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
}
