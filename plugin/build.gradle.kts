plugins {
    java
}

group = "io.github.worldisalsohardcore"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.119-stable")

    // ResetManager は Bukkit の型を参照するので、テスト実行時もクラスパスに要る。
    testImplementation("io.papermc.paper:paper-api:26.2.build.119-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Paper 26.x は Java 25 で動作するため、それに合わせる
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName = "WorldIsAlsoHardcore"
}
