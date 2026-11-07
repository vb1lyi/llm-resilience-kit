// Tests against the real SUT. Three suites, see ADR-004:
//   test            - unit tests, no containers; failures fail the build
//   integrationTest - harness tests; failures fail the build (the suite itself is broken)
//   chaosTest       - scenario tests; failures are results, reported but do not fail the build

plugins {
    id("kit.java-conventions")
}

testing {
    suites {
        val integrationTest = register<JvmTestSuite>("integrationTest") {
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                }
            }
        }

        register<JvmTestSuite>("chaosTest") {
            targets.configureEach {
                testTask.configure {
                    ignoreFailures = true
                    mustRunAfter(integrationTest)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"), testing.suites.named("chaosTest"))
}
