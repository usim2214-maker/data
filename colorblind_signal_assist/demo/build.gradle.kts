import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.signalassist.demo.DemoKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// 공모전 시연 영상 렌더링 (ffmpeg 필요, 한글 폰트: 나눔 글꼴 권장)
tasks.register<JavaExec>("renderVideo") {
    group = "application"
    description = "시나리오를 시연 영상(mp4)으로 렌더링"
    mainClass.set("com.signalassist.demo.VideoKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Djava.awt.headless=true")
    environment("LC_ALL", "C.UTF-8") // 한글 파일 이름
    workingDir = projectDir
}

// 숏폼 세로 영상 (1080x1920)
tasks.register<JavaExec>("renderShorts") {
    group = "application"
    description = "숏폼 세로 영상(mp4) 렌더링"
    mainClass.set("com.signalassist.demo.ShortsKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Djava.awt.headless=true")
    environment("LC_ALL", "C.UTF-8")
    workingDir = projectDir
}

// 부산 센텀시티 실제 도로 주행 영상 (sim/run_centum.py 결과 필요)
tasks.register<JavaExec>("renderCentum") {
    group = "application"
    description = "센텀시티 실제 도로 시뮬레이션 영상(mp4) 렌더링"
    mainClass.set("com.signalassist.demo.CentumVideoKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Djava.awt.headless=true")
    environment("LC_ALL", "C.UTF-8")
    workingDir = projectDir
}
