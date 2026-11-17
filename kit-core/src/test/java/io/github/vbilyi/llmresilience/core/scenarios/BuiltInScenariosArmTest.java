package io.github.vbilyi.llmresilience.core.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Delay;

/**
 * Each built-in scenario's {@code arm()} makes a real MockServer behave as documented. The server runs inside this
 * JVM (no Docker, no SUT); the test plays the SUT's part and calls the OpenAI endpoints directly.
 */
class BuiltInScenariosArmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EMBEDDINGS_REQUEST = """
            {"input": ["Hello World"], "model": "text-embedding-ada-002"}""";

    private static ClientAndServer mock;
    private final HttpClient http = HttpClient.newHttpClient();
    private final RunMarker run = RunMarker.random();

    @BeforeAll
    static void startMockServer() {
        mock = ClientAndServer.startClientAndServer(0); // 0 = any free port
    }

    @AfterAll
    static void stopMockServer() {
        mock.stop();
    }

    @BeforeEach
    void resetMockServer() {
        mock.reset(); // the contract of ChaosScenario.arm(): the caller resets first
    }

    // ---- CONTROL ----

    @Test
    void happyPathAnswersEveryChatCompletionWithTheAnswer() throws Exception {
        new HappyPath().arm(mock, run);

        for (int call = 1; call <= 3; call++) {
            assertAnswer(chat(run), "call " + call);
        }
    }

    @Test
    void happyPathAnswersEmbeddingsWithAnAda002SizedVector() throws Exception {
        new HappyPath().arm(mock, run);

        HttpResponse<String> response = post(OpenAiRequests.EMBEDDINGS_PATH, EMBEDDINGS_REQUEST);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).at("/data/0/embedding"))
                .hasSize(OpenAiResponses.ADA_002_DIMENSIONS);
    }

    // ---- TRANSPORT ----

    @Test
    void rateLimit429RateLimitsEveryChatCompletion() throws Exception {
        new RateLimit429().arm(mock, run);

        for (int call = 1; call <= 3; call++) {
            assertRateLimited(chat(run), "call " + call);
        }
    }

    @Test
    void rateLimit429TransientRateLimitsOnlyTheFirstChatCompletion() throws Exception {
        new RateLimit429Transient().arm(mock, run);

        assertRateLimited(chat(run), "call 1");
        assertAnswer(chat(run), "call 2");
        assertAnswer(chat(run), "call 3");
    }

    @Test
    void serverError503FailsTwiceThenAnswers() throws Exception {
        new ServerError503().arm(mock, run);

        for (int call = 1; call <= ServerError503.FAILURES; call++) {
            HttpResponse<String> response = chat(run);
            assertThat(response.statusCode()).as("call %d", call).isEqualTo(503);
            assertThat(MAPPER.readTree(response.body()).at("/error/type").asText()).isEqualTo("server_error");
        }
        assertAnswer(chat(run), "first call after the failures");
        assertAnswer(chat(run), "second call after the failures");
    }

    @Test
    void serverError503PersistentNeverAnswers() throws Exception {
        new ServerError503Persistent().arm(mock, run);

        for (int call = 1; call <= 4; call++) {
            assertThat(chat(run).statusCode()).as("call %d", call).isEqualTo(503);
        }
    }

    @Test
    void connectionDropClosesTheFirstConnectionThenAnswers() throws Exception {
        new ConnectionDrop().arm(mock, run);

        assertThatThrownBy(() -> chat(run)).isInstanceOf(IOException.class);
        assertAnswer(chat(run), "call 2");
    }

    /** Checked on the stored expectation, not by waiting: the delay is 90 s. */
    @Test
    void timeoutNoFirstTokenDelaysAValidAnswer() {
        new TimeoutNoFirstToken().arm(mock, run);

        Expectation[] armed = mock.retrieveActiveExpectations(OpenAiRequests.chatCompletions());

        assertThat(armed).hasSize(1);
        Delay delay = armed[0].getHttpResponse().getDelay();
        assertThat(delay.getTimeUnit().toSeconds(delay.getValue())).isEqualTo(TimeoutNoFirstToken.DELAY.toSeconds());
        assertThat(delay.getTimeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(armed[0].getHttpResponse().getStatusCode()).isEqualTo(200);
        assertThat(new TimeoutNoFirstToken().timeBudget()).isGreaterThan(TimeoutNoFirstToken.DELAY);
    }

    // ---- PROTOCOL ----

    @Test
    void malformedJsonBodyIsA200WhoseBodyDoesNotParse() throws Exception {
        new MalformedJsonBody().arm(mock, run);

        HttpResponse<String> response = chat(run);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body()).startsWith("{\"id\"");
        assertThatThrownBy(() -> MAPPER.readTree(response.body())).isInstanceOf(JsonProcessingException.class);
    }

    // ---- SEMANTIC ----

    @Test
    void finishReasonLengthReturnsTheCutOffTextWithFinishReasonLength() throws Exception {
        new FinishReasonLength().arm(mock, run);

        JsonNode body = MAPPER.readTree(chat(run).body());

        assertThat(body.at("/choices/0/finish_reason").asText()).isEqualTo("length");
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo(FinishReasonLength.TRUNCATED_ANSWER);
    }

    // ---- TOOLS ----

    @Test
    void toolUnknownNameAsksForTheUnknownToolOnceThenAnswers() throws Exception {
        new ToolUnknownName().arm(mock, run);

        JsonNode first = MAPPER.readTree(chat(run).body());

        assertThat(first.at("/choices/0/finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(first.at("/choices/0/message/tool_calls/0/function/name").asText())
                .isEqualTo(ToolUnknownName.TOOL_NAME);
        assertAnswer(chat(run), "follow-up turn");
    }

    // ---- DRIFT ----

    @Test
    void driftUnknownFieldsKeepsTheAnswerAndAddsFieldsAtEveryLevel() throws Exception {
        new DriftUnknownFields().arm(mock, run);

        HttpResponse<String> response = chat(run);
        JsonNode body = MAPPER.readTree(response.body());

        assertAnswer(response, "drifted response");
        assertThat(body.has("system_fingerprint")).isTrue();
        assertThat(body.has("x_future_feature")).isTrue();
        assertThat(body.at("/choices/0").has("logprobs")).isTrue();
        assertThat(body.at("/choices/0/message").has("refusal")).isTrue();
        assertThat(body.at("/usage").has("completion_tokens_details")).isTrue();
    }

    @Test
    void driftRenamedFieldMovesContentAndFinishReason() throws Exception {
        new DriftRenamedField().arm(mock, run);

        JsonNode choice = MAPPER.readTree(chat(run).body()).at("/choices/0");

        assertThat(choice.has("finish_reason")).isFalse();
        assertThat(choice.path("stop_reason").asText()).isEqualTo("stop");
        assertThat(choice.path("message").has("content")).isFalse();
        assertThat(choice.path("message").path("text").asText()).isEqualTo(HappyPath.ANSWER);
    }

    // ---- every scenario ----

    /** ADR-007: a late request of an earlier run (another marker) gets no armed response - MockServer says 404. */
    @Test
    void noScenarioAnswersChatCompletionsOfAnotherRun() throws Exception {
        List<ChaosScenario> all = new ArrayList<>(BuiltInScenarios.catalog().all());
        all.addAll(BuiltInScenarios.nightly().all());
        RunMarker earlierRun = RunMarker.random();

        for (ChaosScenario scenario : all) {
            mock.reset();
            scenario.arm(mock, run);

            assertThat(chat(earlierRun).statusCode()).as(scenario.id()).isEqualTo(404);
        }
    }

    /** The marker is found whether the client sends content as a string or as an array of parts. */
    @Test
    void theRunIsRecognizedWhenMessageContentIsAnArrayOfParts() throws Exception {
        new HappyPath().arm(mock, run);
        String body = """
                {"model": "gpt-4o",
                 "messages": [{"role": "user",
                               "content": [{"type": "text", "text": "%s"}]}]}""".formatted(run.mark("Hello"));

        assertAnswer(post(OpenAiRequests.CHAT_COMPLETIONS_PATH, body), "array content");
    }

    // ---- helpers ----

    private static void assertAnswer(HttpResponse<String> response, String which) throws Exception {
        assertThat(response.statusCode()).as(which).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).at("/choices/0/message/content").asText()).as(which)
                .isEqualTo(HappyPath.ANSWER);
    }

    private static void assertRateLimited(HttpResponse<String> response, String which) throws Exception {
        assertThat(response.statusCode()).as(which).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).as(which)
                .contains(String.valueOf(RateLimit429.RETRY_AFTER_SECONDS));
        assertThat(MAPPER.readTree(response.body()).at("/error/code").asText()).as(which)
                .isEqualTo("rate_limit_exceeded");
    }

    /** A chat completion request shaped like Spring AI's (spike Q8): history first, an assistant turn without content. */
    private HttpResponse<String> chat(RunMarker marker) throws Exception {
        String body = """
                {"model": "gpt-4o", "stream": false,
                 "messages": [{"role": "user", "content": "\\"an earlier question [llmrk-000000000000]\\""},
                              {"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function",
                                                    "function": {"name": "listOwners", "arguments": "{}"}}]},
                              {"role": "tool", "content": "[]", "name": "listOwners", "tool_call_id": "call_1"},
                              {"role": "assistant", "content": null},
                              {"role": "system", "content": "You are a friendly AI assistant..."},
                              {"role": "user", "content": "\\"%s\\""}]}"""
                .formatted(marker.mark("How many owners do we have?"));
        return post(OpenAiRequests.CHAT_COMPLETIONS_PATH, body);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + mock.getLocalPort() + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();
        return http.send(request, BodyHandlers.ofString());
    }
}
