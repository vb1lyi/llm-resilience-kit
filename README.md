# llm-resilience-kit

A Java testing kit that checks how well an LLM-powered application survives realistic LLM API failures — rate limits, timeouts, malformed responses, broken tool calls, API drift. It builds on [MockServer](https://www.mock-server.com/) and adds a curated catalog of LLM failure scenarios, a JUnit extension that runs one test against every scenario, behavior-focused assertions (including database side effects), and a resilience scorecard. It does not judge answer quality; it tests system behavior. The first system under test is [Spring PetClinic AI](https://github.com/spring-petclinic/spring-petclinic-ai).

**Status: work in progress.** Build with `./gradlew build` (Java 21).

## Running the tests

Requirements: Java 21, Docker, and `git` + network access for the first run (the SUT is cloned and built into an image once).

```bash
./gradlew build
```

- The first run builds the SUT image `llm-resilience/petclinic-ai:<commit>-<pins hash>` from the commit and buildpack images pinned in [`examples/petclinic-ai/sut.properties`](examples/petclinic-ai/sut.properties). Later runs re-use it; force a rebuild with `SUT_FORCE_REBUILD=true ./gradlew :examples:petclinic-ai:buildSutImage`.
- The container-backed suites start MockServer, Postgres and the SUT once per test JVM (~30 s).
- Give Docker at least **4 CPUs / 6 GiB**. With 2 GiB, the three containers barely fit.

**Colima** (macOS) needs Testcontainers pointed at its socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```
