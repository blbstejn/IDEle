import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.riderlan"
version = "1.1.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Compile against the oldest supported platform for broad binary compatibility.
        rider("2024.2")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "dev.riderlan"
        name = "IDEle"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.Rider, "2024.2")
            create(IntelliJPlatformType.Rider, "2025.3")
            create(IntelliJPlatformType.Rider, "2026.2.2")
        }
    }
}

// Run with: gradle integrationTest (does not use JUnit or third-party test libraries).
tasks.register<JavaExec>("integrationTest") {
    group = "verification"
    description = "Run real localhost LAN protocol/game integration tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.riderlan.core.IntegrationTest")
    systemProperty("java.awt.headless", "true")
    dependsOn(tasks.named("testClasses"))
}
