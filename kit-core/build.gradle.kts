// kit-core: SUT-agnostic building blocks - OpenAI payload builders, request matchers, the scenario model and catalog.

plugins {
    id("kit.library-conventions")
}

dependencies {
    api(libs.mockserver.client) // scenarios arm a MockServerClient (ADR-001)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

testing {
    suites.named<JvmTestSuite>("test") {
        dependencies {
            // In-JVM MockServer: scenario arm() tests run against the real thing without Docker.
            implementation(libs.mockserver.netty)
        }
    }
}
