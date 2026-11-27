plugins {
    kotlin("jvm") version "2.4.21"
    application
    jacoco
}

group = "crap4kotlin"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2")
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("crap4kotlin.MainKt")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
    }
}
