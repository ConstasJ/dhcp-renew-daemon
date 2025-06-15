plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.shadow)
    application
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.core)
    implementation(libs.ktor.cio)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j.impl)
    implementation(libs.log4j.config.yaml)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    shadowJar {
        manifest {
            attributes["Main-Class"] = "io.github.constasj.dhcp.AppKt"
        }
        archiveClassifier.set("")
    }
}

application {
    // Define the main class for the application.
    mainClass = "io.github.constasj.dhcp.AppKt"
}
