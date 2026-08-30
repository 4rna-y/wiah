import java.time.Duration

plugins {
    java
}

group = "io.github.worldisalsohardcore"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

// mstore は別リポジトリ。wiah-dev と同じく既定では隣にあるものとして扱う。
val mstoreDir = providers.environmentVariable("MSTORE_DIR").getOrElse("../mstore")
    .let { rootProject.file(it) }
val mstoreBin = mstoreDir.resolve("build/install/mstore/bin/mstore")

val buildMstore = tasks.register<GradleBuild>("buildMstore") {
    description = "隣の checkout にある mstore をビルドする"
    onlyIf { mstoreDir.resolve("settings.gradle.kts").isFile }
    dir = mstoreDir
    tasks = listOf("installDist")
}

// 実サーバーを起動するので数分かかる。gradle build には載せない。
tasks.test {
    enabled = false
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "実際に Paper を起動し、mstore と組み合わせたワールド初期化を検証する (数分かかる)"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(buildMstore, ":plugin:jar")

    useJUnitPlatform()
    // 1つのサーバーの一生を順番に検証するので、並列実行はしない。
    maxParallelForks = 1

    // 外部プロセス (mstore / Paper) と実ファイルを相手にするので、
    // Gradle の入力だけでは結果の鮮度を判断できない。呼ばれたら必ず走らせる。
    outputs.upToDateWhen { false }
    // それとは別に、プラグインを直せば再実行すべきなのを入力として明示しておく。
    inputs.file(project(":plugin").tasks.named<Jar>("jar").flatMap { it.archiveFile })
        .withPropertyName("pluginJar")
    timeout = Duration.ofMinutes(30)

    systemProperty("wiah.mstoreBin", mstoreBin.absolutePath)
    systemProperty("wiah.buildDir", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("wiah.paperCache", rootProject.file("run").absolutePath)
    doFirst {
        val jar = project(":plugin").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        systemProperty("wiah.pluginJar", jar.absolutePath)
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
