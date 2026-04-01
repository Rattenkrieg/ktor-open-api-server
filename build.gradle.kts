plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

group = "io.github.rattenkrieg"
version = "0.1.0-SNAPSHOT"

sourceSets {
    create("testOpenApi") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}

val testOpenApiImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val testOpenApiRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)

    testOpenApiImplementation(libs.openapi.generator)
}

tasks.test {
    useJUnitPlatform()
}

val testOpenApi by tasks.registering(Test::class) {
    testClassesDirs = sourceSets["testOpenApi"].output.classesDirs
    classpath = sourceSets["testOpenApi"].runtimeClasspath
    useJUnitPlatform()
}


kotlin {
    jvmToolchain(21)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(group.toString(), "ktor-open-api-server", version.toString())

    pom {
        name.set("Ktor Open API Server")
        description.set("Type-safe request/response handling for Ktor with automatic OpenAPI 3.1 spec generation")
        url.set("https://github.com/Rattenkrieg/ktor-open-api-server")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("rattenkrieg")
                name.set("Sergey")
                url.set("https://github.com/Rattenkrieg")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/Rattenkrieg/ktor-open-api-server.git")
            developerConnection.set("scm:git:ssh://git@github.com:Rattenkrieg/ktor-open-api-server.git")
            url.set("https://github.com/Rattenkrieg/ktor-open-api-server")
        }
    }
}
