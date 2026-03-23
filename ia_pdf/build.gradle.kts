plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:2.0.30")
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-html-builder-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.0")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.0.0")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
