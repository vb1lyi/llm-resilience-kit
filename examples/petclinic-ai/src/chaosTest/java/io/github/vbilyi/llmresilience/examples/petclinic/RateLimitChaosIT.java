package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429Transient;
import io.github.vbilyi.llmresilience.core.sut.ChatDriver;
import io.github.vbilyi.llmresilience.core.sut.ChatResult;
import io.github.vbilyi.llmresilience.junit5.LlmChaosSuite;
import io.github.vbilyi.llmresilience.junit5.LlmChaosTest;
import java.time.Duration;
import org.assertj.core.api.SoftAssertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RATE_LIMIT_429 and RATE_LIMIT_429_TRANSIENT. Chaos tests (ADR-004): a failure here is a <b>finding</b> about the
 * SUT, not a bug in the suite. Runs against the SUT's shipped retry defaults (ADR-005).
 *
 * <p>The extension resets the environment, arms the scenario, and afterwards checks that the SUT still answers a
 * normal message. The driver gives up after the scenario's time budget, so a SUT that hangs cannot hang the run.
 * The checks are still written inline here; the generic evaluator arrives in Stage 7. Every run logs what actually
 * happened (attempts, gaps, duration, status, body) before asserting.
 */
@LlmChaosSuite(environment = PetClinicAiEnvironment.class)
class RateLimitChaosIT {

    private static final Logger log = LoggerFactory.getLogger(RateLimitChaosIT.class);

    private static final Duration GAP_TOLERANCE = Duration.ofMillis(100); // container clock vs schedule (spike Q9)

    @LlmChaosTest(scenarios = RateLimit429.ID)
    void persistentRateLimit_userGetsAReadableMessage(ChaosScenario scenario, ChatDriver chat) {
        ChatResult result = sendAndLog(scenario, chat);

        SoftAssertions softly = new SoftAssertions();
        assertReadableForTheUser(softly, result);
        assertHonorsRetryAfter(softly, result);
        softly.assertAll();
    }

    @LlmChaosTest(scenarios = RateLimit429Transient.ID)
    void rateLimitOnceThenSuccess_userGetsTheAnswer(ChaosScenario scenario, ChatDriver chat) {
        ChatResult result = sendAndLog(scenario, chat);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result.status()).as("HTTP status the user got").isEqualTo(200);
        softly.assertThat(result.body()).as("what the user saw").isEqualTo(HappyPath.ANSWER);
        assertHonorsRetryAfter(softly, result);
        softly.assertAll();
    }

    // ---- helpers (inline until the evaluator: Stage 7) ----

    private static ChatResult sendAndLog(ChaosScenario scenario, ChatDriver chat) {
        ChatResult result = chat.send("How many owners do we have?");
        log.info("[{}] timedOut={} status={} duration={} ms llmCalls={} gapsMs={} body={}",
                scenario.id(), result.timedOut(), result.status(), result.duration().toMillis(), result.llmCalls(),
                result.gapsBetweenLlmCalls().stream().map(Duration::toMillis).toList(), result.body());
        return result;
    }

    private static void assertReadableForTheUser(SoftAssertions softly, ChatResult result) {
        softly.assertThat(result.replied()).as("the user got a reply (timed out: %s, error: %s)",
                result.timedOut(), result.transportError()).isTrue();
        softly.assertThat(result.status()).as("HTTP status the user got (500 = unhandled exception)").isNotEqualTo(500);
        softly.assertThat(result.body()).as("what the user saw").isNotBlank();
        softly.assertThat(looksLikeRawSpringError(result.body()))
                .as("user saw raw Spring error JSON: %s", result.body())
                .isFalse();
    }

    private static void assertHonorsRetryAfter(SoftAssertions softly, ChatResult result) {
        Duration minGap = Duration.ofSeconds(RateLimit429.RETRY_AFTER_SECONDS).minus(GAP_TOLERANCE);
        softly.assertThat(result.gapsBetweenLlmCalls())
                .as("gaps between LLM attempts must be >= Retry-After (%d s)", RateLimit429.RETRY_AFTER_SECONDS)
                .allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(minGap));
    }

    /** Spring Boot's default error body: {"timestamp":…,"status":500,"error":"Internal Server Error","path":"/chat"}. */
    private static boolean looksLikeRawSpringError(String body) {
        String b = body.strip();
        return b.startsWith("{") && b.contains("\"timestamp\"") && b.contains("\"status\"") && b.contains("\"path\"");
    }
}
