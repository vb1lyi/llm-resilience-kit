rootProject.name = "llm-resilience-kit"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    "kit-core",
    "kit-junit5",
    "kit-report",
    "examples:petclinic-ai",
)
// spike/ (Stage 1) is frozen evidence and intentionally not part of the build - see spike/README.md
