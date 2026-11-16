package io.github.vbilyi.llmresilience.examples.petclinic;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429Transient;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockserver.model.LogEventRequestAndResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RATE_LIMIT_429 and RATE_LIMIT_429_TRANSIENT. Chaos tests (ADR-004): a failure here is a <b>finding</b> about the
 * SUT, not a bug in the suite. Runs against the SUT's shipped retry defaults (ADR-005).
 *
 * <p>Arming and the agreed expectations come from kit-core ({@link RateLimit429}, {@link RateLimit429Transient}).
 * The checks are still evaluated inline here; the generic evaluator arrives in Stage 7. Every run logs what actually
 * happened (attempts, gaps, duration, status, body) before asserting.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES) // backstop only: it interrupts the thread, which a socket read ignores
class RateLimitChaosIT {

    private static final Logger log = LoggerFactory.getLogger(RateLimitChaosIT.class);

    private static final long GAP_TOLERANCE_MS = 100; // container clock vs schedule (spike Q9)

    /**
     * The SUT has no LLM read timeout (spike Q7), so the client must give up by itself: without a read timeout a
     * blocked socket read outlives JUnit's {@code @Timeout}. A timeout here fails the test - which is the finding.
     */
    private static final RestAssuredConfig GIVE_UP_AFTER_4_MINUTES = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofMinutes(4)));

    // MockServer timestamps: "2026-11-02 18:51:00.485" (container clock, ms precision; spike Q9).
    private static final DateTimeFormatter MOCK_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static PetClinicAiEnvironment env;

    @BeforeAll
    static void startEnvironment() {
        env = PetClinicAiEnvironment.shared();
    }

    @BeforeEach
    void resetState() {
        env.resetState();
    }

    @Test
    void persistentRateLimit_userGetsAReadableMessage() {
        Observation o = run(new RateLimit429());

        SoftAssertions softly = new SoftAssertions();
        assertReadableForTheUser(softly, o);
        assertHonorsRetryAfter(softly, o);
        softly.assertAll();
    }

    @Test
    void rateLimitOnceThenSuccess_userGetsTheAnswer() {
        Observation o = run(new RateLimit429Transient());

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(o.status()).as("HTTP status the user got").isEqualTo(200);
        softly.assertThat(o.body()).as("what the user saw").isEqualTo(HappyPath.ANSWER);
        assertHonorsRetryAfter(softly, o);
        softly.assertAll();
    }

    // ---- helpers (inline until the kit's driver and evaluator: Stages 6-7) ----

    private Observation run(ChaosScenario scenario) {
        RunMarker marker = RunMarker.random();
        scenario.arm(env.mockServer(), marker);

        long t0 = System.nanoTime();
        Response reply = given().config(GIVE_UP_AFTER_4_MINUTES)
                .baseUri(env.baseUrl())
                .contentType("application/json")
                .body("\"" + marker.mark("How many owners do we have?") + "\"")
                .when().post("/chat");
        Duration duration = Duration.ofNanos(System.nanoTime() - t0);

        List<LocalDateTime> calls = llmCallTimes(marker);
        Observation o = new Observation(reply.statusCode(), reply.asString(), duration, calls.size(), gaps(calls));
        log.info("[{}] status={} duration={} ms llmCalls={} gapsMs={} body={}",
                scenario.id(), o.status(), o.duration().toMillis(), o.llmCalls(), o.gapsMs(), o.body());
        return o;
    }

    /** When the SUT called the LLM for this run, from MockServer's recorded timestamps, oldest first. */
    private static List<LocalDateTime> llmCallTimes(RunMarker marker) {
        return Arrays.stream(env.mockServer().retrieveRecordedRequestsAndResponses(marker.chatCompletions()))
                .map(LogEventRequestAndResponse::getTimestamp)
                .map(ts -> LocalDateTime.parse(ts, MOCK_TIMESTAMP))
                .sorted()
                .toList();
    }

    private static List<Long> gaps(List<LocalDateTime> times) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            gaps.add(Duration.between(times.get(i - 1), times.get(i)).toMillis());
        }
        return gaps;
    }

    private static void assertReadableForTheUser(SoftAssertions softly, Observation o) {
        softly.assertThat(o.status()).as("HTTP status the user got (500 = unhandled exception)").isNotEqualTo(500);
        softly.assertThat(o.body()).as("what the user saw").isNotBlank();
        softly.assertThat(looksLikeRawSpringError(o.body()))
                .as("user saw raw Spring error JSON: %s", o.body())
                .isFalse();
    }

    private static void assertHonorsRetryAfter(SoftAssertions softly, Observation o) {
        long minGap = RateLimit429.RETRY_AFTER_SECONDS * 1000L - GAP_TOLERANCE_MS;
        softly.assertThat(o.gapsMs())
                .as("gaps between LLM attempts (ms) must be >= Retry-After (%d s)", RateLimit429.RETRY_AFTER_SECONDS)
                .allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(minGap));
    }

    /** Spring Boot's default error body: {"timestamp":…,"status":500,"error":"Internal Server Error","path":"/chat"}. */
    private static boolean looksLikeRawSpringError(String body) {
        String b = body.strip();
        return b.startsWith("{") && b.contains("\"timestamp\"") && b.contains("\"status\"") && b.contains("\"path\"");
    }

    /** What one chat message produced, as seen by the user and by the mock. */
    private record Observation(int status, String body, Duration duration, int llmCalls, List<Long> gapsMs) {}
}
