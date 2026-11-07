package io.github.vbilyi.llmresilience.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonPathBody.jsonPath;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpError;
import org.mockserver.model.LogEventRequestAndResponse;
import org.testcontainers.mockserver.MockServerContainer;
import org.testcontainers.utility.DockerImageName;

/** Stage 1 spike (Q9): verifies each MockServer capability the kit depends on. Throwaway code. */
class MockServerCapabilitiesTest {

    private static final String COMPLETIONS = "/v1/chat/completions";

    private static final String CANNED_COMPLETION = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from the mock"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private static MockServerContainer container;
    private static MockServerClient mock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() {
        container = new MockServerContainer(DockerImageName.parse(System.getProperty("mockserver.image")));
        long t0 = System.nanoTime();
        container.start();
        System.out.printf("[spike] MockServer %s started in %d ms%n", container.getDockerImageName(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        mock = new MockServerClient(container.getHost(), container.getServerPort());
    }

    @AfterAll
    static void stop() {
        container.stop();
    }

    @BeforeEach
    void reset() {
        mock.reset();
    }

    @Test
    void returnsCannedChatCompletion() throws Exception {
        mock.when(request().withMethod("POST").withPath(COMPLETIONS))
                .respond(response().withStatusCode(200).withHeader("Content-Type", "application/json")
                        .withBody(CANNED_COMPLETION));

        HttpResponse<String> r = post(COMPLETIONS, "{\"messages\":[]}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("Hello from the mock");
    }

    @Test
    void returns429WithRetryAfter() throws Exception {
        mock.when(request().withPath(COMPLETIONS))
                .respond(response().withStatusCode(429).withHeader("Retry-After", "2")
                        .withBody("{\"error\":{\"message\":\"Rate limit\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}"));

        HttpResponse<String> r = post(COMPLETIONS, "{}");

        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("Retry-After")).hasValue("2");
    }

    @Test
    void delaysResponse() throws Exception {
        mock.when(request().withPath(COMPLETIONS))
                .respond(response().withStatusCode(200).withBody(CANNED_COMPLETION).withDelay(Delay.seconds(2)));

        long t0 = System.nanoTime();
        post(COMPLETIONS, "{}");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        System.out.printf("[spike] delayed response took %d ms (configured 2000)%n", ms);
        // Tolerance: the delay is scheduled inside the container (VM clock), measured on the host.
        // Observed 1,997–2,020 ms; a strict 2,000 ms lower bound is flaky.
        assertThat(ms).isBetween(1900L, 3000L);
    }

    @Test
    void sequencesResponsesInOrder() throws Exception {
        // Fail twice, then succeed: the shape decided for SERVER_ERROR_503 (D1).
        mock.when(request().withPath(COMPLETIONS), Times.exactly(2)).respond(response().withStatusCode(503));
        mock.when(request().withPath(COMPLETIONS)).respond(response().withStatusCode(200).withBody(CANNED_COMPLETION));

        int[] statuses = { post(COMPLETIONS, "{}").statusCode(), post(COMPLETIONS, "{}").statusCode(),
                post(COMPLETIONS, "{}").statusCode(), post(COMPLETIONS, "{}").statusCode() };

        System.out.println("[spike] sequenced statuses: " + Arrays.toString(statuses));
        assertThat(statuses).containsExactly(503, 503, 200, 200);
    }

    @Test
    void matchesOnLastMessageOnlyWithJsonPath() throws Exception {
        // D2: history is shared, so match on the LAST message, never "anywhere in the body".
        mock.when(request().withPath(COMPLETIONS).withBody(jsonPath("$.messages[-1:][?(@.role == 'tool')]")))
                .respond(response().withStatusCode(200).withBody("FOLLOW_UP"));
        mock.when(request().withPath(COMPLETIONS)).respond(response().withStatusCode(200).withBody("FIRST_TURN"));

        String toolInHistoryLastIsUser = """
                {"messages":[{"role":"user","content":"a"},{"role":"tool","content":"old"},{"role":"user","content":"b"}]}""";
        String lastIsTool = """
                {"messages":[{"role":"user","content":"a"},{"role":"assistant","tool_calls":[]},{"role":"tool","content":"r"}]}""";

        assertThat(post(COMPLETIONS, toolInHistoryLastIsUser).body()).isEqualTo("FIRST_TURN");
        assertThat(post(COMPLETIONS, lastIsTool).body()).isEqualTo("FOLLOW_UP");
    }

    @Test
    void recordsRequestsWithTimestamps() throws Exception {
        mock.when(request().withPath(COMPLETIONS)).respond(response().withStatusCode(200));

        post(COMPLETIONS, "{\"n\":1}");
        Thread.sleep(1500);
        post(COMPLETIONS, "{\"n\":2}");

        LogEventRequestAndResponse[] recorded = mock.retrieveRecordedRequestsAndResponses(request().withPath(COMPLETIONS));
        assertThat(recorded).hasSize(2);
        for (LogEventRequestAndResponse e : recorded) {
            System.out.printf("[spike] recorded at '%s' body=%s%n", e.getTimestamp(), e.getHttpRequest().getBodyAsString());
        }
        assertThat(recorded[0].getTimestamp()).isNotBlank();
        assertThat(recorded[1].getTimestamp()).isNotBlank();
    }

    @Test
    void dropsConnection() {
        mock.when(request().withPath(COMPLETIONS)).error(HttpError.error().withDropConnection(true));

        assertThatThrownBy(() -> post(COMPLETIONS, "{}")).isInstanceOf(IOException.class);
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(container.getEndpoint() + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build();
        return http.send(req, BodyHandlers.ofString());
    }
}
