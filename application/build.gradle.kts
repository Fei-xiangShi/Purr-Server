plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":domain"))
    testImplementation(kotlin("test"))
}
