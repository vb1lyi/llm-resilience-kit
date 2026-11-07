// Conventions shared by every Java module: Java 21, JUnit Jupiter + AssertJ in every test suite.

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

testing {
    suites.withType<JvmTestSuite>().configureEach {
        useJUnitJupiter(libs.findVersion("junit").get().requiredVersion)
        dependencies {
            implementation(libs.findLibrary("assertj-core").get())
        }
        targets.configureEach {
            testTask.configure {
                testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }
}
