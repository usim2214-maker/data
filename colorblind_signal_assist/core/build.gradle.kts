import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging { events("passed", "failed", "skipped") }
    // 실제 도로 주행 기록이 바뀌면 테스트를 다시 돌리도록 입력으로 등록
    inputs.files(fileTree("../sim/data") { include("centum_trace.csv") }).withPropertyName("simTrace").optional()
}
