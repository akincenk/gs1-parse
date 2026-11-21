plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "io.github.akincenk"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.akincenk.gs1.CliKt")
    applicationName = "gs1-parse"
}

tasks.test {
    useJUnitPlatform()
}
