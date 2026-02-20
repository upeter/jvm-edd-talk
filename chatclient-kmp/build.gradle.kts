import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    id("org.jetbrains.compose") version "1.8.1"

}

group = "dev.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jitpack.io")
    google()
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                // Ktor client for API calls
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-cio:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-jackson:3.1.3")
                implementation("io.ktor:ktor-client-core-jvm:3.1.3")

                // Jackson for JSON serialization
                implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")

                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

                // MP3 playback
                implementation("com.github.umjammer:jlayer:1.0.3")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
                implementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")

                // Ktor client for API calls (same as main)
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-cio:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-jackson:3.1.3")

                // Jackson for JSON serialization (same as main)
                implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")

                // Coroutines (same as main)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.course.llm.chatapp.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "chatclient-kmp"
            packageVersion = "1.0.0"
        }
    }
}
