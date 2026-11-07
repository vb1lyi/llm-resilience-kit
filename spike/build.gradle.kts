// TEMPORARY (Stage 1 spike): throwaway MockServer capability checks. Delete before Stage 2.

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
