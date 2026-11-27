// Maven Central 429 on shared CI runners: mirror first, portal for plugin markers.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "crap4kotlin"
