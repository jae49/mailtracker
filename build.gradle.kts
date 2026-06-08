import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "com.mailtracker"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    // Microsoft auth (app-only, certificate credential)
    implementation("com.microsoft.azure:msal4j:1.21.0")
    // JSON (models + --json output)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Embedded SQLite
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    // Minimal logging
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

application {
    mainClass = "com.mailtracker.MainKt"
    // Silence Java 25 FFM "restricted method" warnings from sqlite-jdbc and JNA (mordant).
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
}
