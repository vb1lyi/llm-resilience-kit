# llm-resilience-kit

A Java testing kit that checks how well an LLM-powered application survives realistic LLM API failures — rate limits, timeouts, malformed responses, broken tool calls, API drift. It builds on [MockServer](https://www.mock-server.com/) and adds a curated catalog of LLM failure scenarios, a JUnit extension that runs one test against every scenario, behavior-focused assertions (including database side effects), and a resilience scorecard. It does not judge answer quality; it tests system behavior. The first system under test is [Spring PetClinic AI](https://github.com/spring-petclinic/spring-petclinic-ai).

**Status: work in progress.** Build with `./gradlew build` (Java 21).
