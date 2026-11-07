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
    "spike", // TEMPORARY - Stage 1 throwaway checks; remove before Stage 2
)
