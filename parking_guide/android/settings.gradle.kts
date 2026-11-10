pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK (Vehicle / TextToSpeech / FusedLocation)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "ParkingGuide"
include(":core")
// Android SDK가 있을 때만 앱 모듈을 포함한다 (core 테스트는 SDK 없이도 돌릴 수 있게)
if (file("local.properties").exists() || System.getenv("ANDROID_HOME") != null) {
    include(":app")
}
