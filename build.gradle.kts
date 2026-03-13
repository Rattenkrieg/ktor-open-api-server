plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.sergey"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("devServer") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("openapi.dev.DevServerKt")
}

kotlin {
    jvmToolchain(21)
}
