// FROZEN (Stage 1 spike): not included in settings.gradle.kts. To re-run, temporarily add "spike" to include(...).

plugins {
    id("kit.java-conventions")
}

dependencies {
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.mockserver)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.mockserver.client)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    systemProperty("mockserver.image", "mockserver/mockserver:${libs.versions.mockserver.get()}")
    testLogging { showStandardStreams = true }
}
