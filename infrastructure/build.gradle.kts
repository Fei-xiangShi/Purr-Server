plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    implementation(project(":application"))

    api(libs.java.jwt)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.bcrypt)
    implementation(libs.livekit.server)
    implementation(libs.lettuce)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.google.auth.oauth2.http)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
    testImplementation(kotlin("test"))
}
