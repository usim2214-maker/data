pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") // Maven Central 구글 미러 (429 회피)
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") // Maven Central 구글 미러 (429 회피)
        mavenCentral()
        // Pleos Playground SDK 저장소 (공식 샘플 pleos-sample-fusedlocation-app 기준)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
rootProject.name = "PleosCarDex"
include(":app")
