// kit-junit5: the JUnit Jupiter extension - @LlmChaosSuite / @LlmChaosTest run one test method once per scenario.

plugins {
    id("kit.library-conventions")
}

dependencies {
    api(project(":kit-core"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
}

testing {
    suites.named<JvmTestSuite>("test") {
        dependencies {
            implementation(libs.junit.platform.testkit) // runs the fixture classes through the real Jupiter engine
            implementation(libs.mockserver.netty) // in-JVM MockServer for the fake environment
        }
        targets.configureEach {
            testTask.configure {
                // Fixture classes fail on purpose; they are only run through EngineTestKit by the real tests.
                exclude("**/fixtures/**")
            }
        }
    }
}
