package io.github.vbilyi.llmresilience.examples.petclinic;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

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
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RATE_LIMIT_429, written by hand (Stage 3). A chaos test (ADR-004): a failure here is a <b>finding</b> about the SUT,
 * not a bug in the suite. Runs against the SUT's shipped retry defaults (ADR-005).
 *
 * <p>Expected behaviour (agreed 2026-11-07, see the open-questions log):
 * <ol>
 *   <li>the user gets a readable message — no HTTP 500 and no raw Spring error JSON;</li>
 *   <li>if the SUT retries, it waits at least {@code Retry-After} between attempts;</li>
 *   <li>when the rate limit clears after one 429, the user gets the real answer.</li>
 * </ol>
 * Every run logs what actually happened (attempts, gaps, duration, status, body) before asserting.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES) // the SUT has no LLM read timeout (spike Q7): never hang the run
class RateLimitChaosIT {

    private static final Logger log = LoggerFactory.getLogger(RateLimitChaosIT.class);

    private static final int RETRY_AFTER_SECONDS = 2;
    private static final long GAP_TOLERANCE_MS = 100; // container clock vs schedule (spike Q9)
    private static final String ANSWER = "We have 10 owners registered at the clinic.";

    private static final HttpRequest CHAT_COMPLETIONS =
            request().withMethod("POST").withPath("/v1/chat/completions");

    // What OpenAI sends on a rate limit: 429, Retry-After, and an error object.
    private static final HttpResponse RATE_LIMITED = response().withStatusCode(429)
            .withHeader("Content-Type", "application/json")
            .withHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS))
            .withBody("""
                    {"error": {"message": "Rate limit reached for gpt-4o on requests per min (RPM): Limit 500.",
                               "type": "requests", "param": null, "code": "rate_limit_exceeded"}}""");

    private static final HttpResponse COMPLETION = response().withStatusCode(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                    {"id": "chatcmpl-rate-limit", "object": "chat.completion", "created": 1762300000, "model": "gpt-4o",
                     "choices": [{"index": 0, "message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}],
                     "usage": {"prompt_tokens": 42, "completion_tokens": 9, "total_tokens": 51}}""".formatted(ANSWER));

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
        env.mockServer().when(CHAT_COMPLETIONS).respond(RATE_LIMITED);

        Observation o = sendChatMessage("persistent 429");

        SoftAssertions softly = new SoftAssertions();
        assertReadableForTheUser(softly, o);
        assertHonoursRetryAfter(softly, o);
        softly.assertAll();
    }

    @Test
    void rateLimitOnceThenSuccess_userGetsTheAnswer() {
        env.mockServer().when(CHAT_COMPLETIONS, Times.exactly(1)).respond(RATE_LIMITED);
        env.mockServer().when(CHAT_COMPLETIONS).respond(COMPLETION);

        Observation o = sendChatMessage("429 once, then success");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(o.status()).as("HTTP status the user got").isEqualTo(200);
        softly.assertThat(o.body()).as("what the user saw").isEqualTo(ANSWER);
        assertHonoursRetryAfter(softly, o);
        softly.assertAll();
    }

    // ---- helpers (inline on purpose in Stage 3; they become kit code in Stages 4-7) ----

    private Observation sendChatMessage(String variant) {
        long t0 = System.nanoTime();
        Response reply = given().baseUri(env.baseUrl())
                .contentType("application/json")
                .body("\"How many owners do we have?\"")
                .when().post("/chat");
        Duration duration = Duration.ofNanos(System.nanoTime() - t0);

        List<LocalDateTime> calls = llmCallTimes();
        Observation o = new Observation(reply.statusCode(), reply.asString(), duration, calls.size(), gaps(calls));
        log.info("[RATE_LIMIT_429 / {}] status={} duration={} ms llmCalls={} gapsMs={} body={}",
                variant, o.status(), o.duration().toMillis(), o.llmCalls(), o.gapsMs(), o.body());
        return o;
    }

    /** When the SUT called the LLM, from MockServer's recorded timestamps, oldest first. */
    private static List<LocalDateTime> llmCallTimes() {
        return Arrays.stream(env.mockServer().retrieveRecordedRequestsAndResponses(CHAT_COMPLETIONS))
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

    private static void assertHonoursRetryAfter(SoftAssertions softly, Observation o) {
        long minGap = RETRY_AFTER_SECONDS * 1000L - GAP_TOLERANCE_MS;
        softly.assertThat(o.gapsMs())
                .as("gaps between LLM attempts (ms) must be >= Retry-After (%d s)", RETRY_AFTER_SECONDS)
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
