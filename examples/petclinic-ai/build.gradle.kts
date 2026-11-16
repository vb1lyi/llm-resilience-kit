// Tests against the real SUT. Three suites, see ADR-004:
//   test            - unit tests, no containers; failures fail the build
//   integrationTest - harness tests; failures fail the build (the suite itself is broken)
//   chaosTest       - scenario tests; failures are results, reported but do not fail the build
//
// src/main holds the test environment (PetClinicAiEnvironment) shared by integrationTest and chaosTest.

import java.io.StringReader
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Properties

plugins {
    id("kit.java-conventions")
    `java-library`
}

// SUT + image pins: examples/petclinic-ai/sut.properties is the single source.
val sut = Properties().apply {
    load(StringReader(providers.fileContents(layout.projectDirectory.file("sut.properties")).asText.get()))
}
fun sut(key: String): String = requireNotNull(sut.getProperty(key)) { "sut.properties: missing '$key'" }

// Image tag = <commit, 7 chars>-<hash of the buildpack pins, 8 chars>. Derived, never typed by hand: a changed pin
// gives a new tag, so build-image.sh cannot mistake an image built from older pins for the current one.
val sutImage: String = run {
    val pins = sut("sut.builder") + "\n" + sut("sut.run-image")
    val pinsHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pins.toByteArray()))
    "${sut("sut.image-name")}:${sut("sut.commit").take(7)}-${pinsHash.take(8)}"
}

dependencies {
    api(project(":kit-core"))
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.core)
    api(libs.testcontainers.mockserver)
    api(libs.testcontainers.postgresql)
    api(libs.mockserver.client)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.postgresql.jdbc)
}

val buildSutImage = tasks.register<Exec>("buildSutImage") {
    group = "sut"
    description = "Builds the PetClinic AI image from the pinned commit (skipped if the image already exists)."
    commandLine("bash", layout.projectDirectory.file("sut/build-image.sh").asFile.path)
    environment(
        mapOf(
            "SUT_REPOSITORY" to sut("sut.repository"),
            "SUT_COMMIT" to sut("sut.commit"),
            "SUT_IMAGE" to sutImage,
            "SUT_BUILDER" to sut("sut.builder"),
            "SUT_RUN_IMAGE" to sut("sut.run-image"),
            "SUT_CHECKOUT_DIR" to rootProject.layout.projectDirectory.dir(".sut/spring-petclinic-ai").asFile.path,
        ),
    )
    outputs.upToDateWhen { false } // the script itself checks whether the image exists
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

        named<JvmTestSuite>("test") {
            dependencies {
                implementation(project())
                implementation(libs.mockserver.netty) // in-JVM MockServer for the scenarios' arm() tests
            }
        }

        // Both container-backed suites get the environment, its images, and logging.
        listOf("integrationTest", "chaosTest").forEach { name ->
            named<JvmTestSuite>(name) {
                dependencies {
                    implementation(project())
                    implementation(libs.rest.assured)
                    runtimeOnly(libs.logback.classic)
                }
                targets.configureEach {
                    testTask.configure {
                        dependsOn(buildSutImage)
                        systemProperty("sut.image", sutImage)
                        systemProperty("db.image", sut("db.image"))
                        systemProperty("mockserver.image", "mockserver/mockserver:${libs.versions.mockserver.get()}")
                        // The real environment is never re-used between runs: always run against fresh containers.
                        outputs.upToDateWhen { false }
                        // Backstop against a hung run: the SUT has no LLM read timeout (spike Q7).
                        timeout = Duration.ofMinutes(30)
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"), testing.suites.named("chaosTest"))
}
