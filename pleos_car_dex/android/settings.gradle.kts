pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // TODO(Pleos): Pleos Playground 에서 안내하는 SDK 저장소(또는 로컬 aar)를 여기에 추가
        // maven { url = uri("<Pleos SDK maven url>") }
        flatDir { dirs("app/libs") }
    }
}
rootProject.name = "PleosCarDex"
include(":app")
