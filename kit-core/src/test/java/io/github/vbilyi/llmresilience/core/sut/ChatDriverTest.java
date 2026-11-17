package io.github.vbilyi.llmresilience.core.sut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;
import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429Transient;
import io.github.vbilyi.llmresilience.core.scenarios.TimeoutNoFirstToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;

/** The driver against an in-JVM MockServer and a scripted "SUT" that calls the LLM a given number of times. */
class ChatDriverTest {

    private static ClientAndServer mock;
    private final RunMarker run = RunMarker.random();

    @BeforeAll
    static void startMockServer() {
        mock = ClientAndServer.startClientAndServer(0);
    }

    @AfterAll
    static void stopMockServer() {
        mock.stop();
    }

    @BeforeEach
    void resetMockServer() {
        mock.reset();
    }

    @Test
    void marksTheMessageAndRecordsReplyAndLlmCalls() {
        ScriptedSut sut = new ScriptedSut(1);
        new HappyPath().arm(mock, run);

        ChatResult result = new ChatDriver(sut, new HappyPath(), run).send("Hello");

        assertThat(sut.received).containsExactly("Hello [" + run.value() + "]");
        assertThat(sut.timeouts).containsExactly(Duration.ofMinutes(1)); // the scenario's time budget
        assertThat(result.userMessage()).isEqualTo("Hello [" + run.value() + "]");
        assertThat(result.replied()).isTrue();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("reply after 1 LLM call(s), last status 200");
        assertThat(result.llmCalls()).isEqualTo(1);
        assertThat(result.gapsBetweenLlmCalls()).isEmpty();
        assertThat(result.duration()).isPositive();
    }

    @Test
    void readsCallTimesFromTheMockOldestFirstWithTheGapsBetweenThem() {
        ScriptedSut sut = new ScriptedSut(2); // "retries" once, after 300 ms
        new RateLimit429Transient().arm(mock, run);

        ChatResult result = new ChatDriver(sut, new RateLimit429Transient(), run).send("Hello");

        assertThat(result.body()).isEqualTo("reply after 2 LLM call(s), last status 200");
        assertThat(result.llmCalls()).isEqualTo(2);
        assertThat(result.llmCallTimes()).isSorted();
        assertThat(result.gapsBetweenLlmCalls()).singleElement()
                .satisfies(gap -> assertThat(gap).isBetween(Duration.ofMillis(250), Duration.ofSeconds(5)));
    }

    @Test
    void countsOnlyTheRequestsOfItsOwnRun() {
        ScriptedSut sut = new ScriptedSut(1);
        new HappyPath().arm(mock, run);
        sut.callLlm("a late request of an earlier run [llmrk-000000000000]");

        ChatResult result = new ChatDriver(sut, new HappyPath(), run).send("Hello");

        assertThat(mock.retrieveRecordedRequests(OpenAiRequests.chatCompletions())).hasSize(2);
        assertThat(result.llmCalls()).isEqualTo(1);
    }

    @Test
    void eachResultHoldsOnlyTheLlmCallsOfItsOwnMessage() {
        ScriptedSut sut = new ScriptedSut(2);
        new HappyPath().arm(mock, run);
        ChatDriver driver = new ChatDriver(sut, new HappyPath(), run);

        ChatResult first = driver.send("First");
        ChatResult second = driver.send("Second");

        assertThat(first.llmCalls()).isEqualTo(2);
        assertThat(second.llmCalls()).isEqualTo(2); // not 4
        assertThat(second.llmCallTimes()).doesNotContainAnyElementsOf(first.llmCallTimes().subList(0, 1));
        assertThat(second.gapsBetweenLlmCalls()).hasSize(1);
    }

    @Test
    void aSutThatDropsTheConnectionIsRecordedNotThrown() {
        ScriptedSut sut = new ScriptedSut(1) {
            @Override
            public ChatReply chat(String userMessage, Duration timeout) {
                super.chat(userMessage, timeout);
                throw new IllegalStateException("Connection reset");
            }
        };
        new HappyPath().arm(mock, run);

        ChatResult result = new ChatDriver(sut, new HappyPath(), run).send("Hello");

        assertThat(result.replied()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.transportError()).contains("Connection reset");
        assertThat(result.status()).isZero();
        assertThat(result.llmCalls()).isEqualTo(1); // the evidence is still collected
    }

    @Test
    void aSutThatDoesNotReplyInTimeIsRecordedAsTimedOutNotThrown() {
        ScriptedSut sut = new ScriptedSut(0) {
            @Override
            public ChatReply chat(String userMessage, Duration timeout) {
                super.chat(userMessage, timeout);
                throw new ChatTimeoutException(timeout, null);
            }
        };
        ChatDriver driver = new ChatDriver(sut, new TimeoutNoFirstToken(), run);

        ChatResult result = driver.send("Hello");

        assertThat(sut.timeouts).containsExactly(new TimeoutNoFirstToken().timeBudget());
        assertThat(result.timedOut()).isTrue();
        assertThat(result.replied()).isFalse();
        assertThat(result.status()).isZero();
        assertThat(result.body()).isEmpty();
        assertThat(driver.results()).containsExactly(result);
    }

    /** A fake SUT: for each chat message it calls the LLM {@code llmCalls} times, 300 ms apart. */
    private static class ScriptedSut implements ChaosEnvironment {

        final List<String> received = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        private final int llmCalls;
        private final HttpClient http = HttpClient.newHttpClient();

        ScriptedSut(int llmCalls) {
            this.llmCalls = llmCalls;
        }

        @Override
        public ChatReply chat(String userMessage, Duration timeout) {
            received.add(userMessage);
            timeouts.add(timeout);
            int lastStatus = 0;
            for (int call = 1; call <= llmCalls; call++) {
                if (call > 1) {
                    pause();
                }
                lastStatus = callLlm(userMessage);
            }
            return new ChatReply(200, "reply after " + llmCalls + " LLM call(s), last status " + lastStatus);
        }

        int callLlm(String userMessage) {
            String body = "{\"messages\": [{\"role\": \"user\", \"content\": \"" + userMessage + "\"}]}";
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + mock.getLocalPort() + OpenAiRequests.CHAT_COMPLETIONS_PATH))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
                return response.statusCode();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static void pause() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public MockServerClient mockServer() {
            return mock;
        }

        @Override
        public ScenarioCatalog scenarios() {
            return ScenarioCatalog.of(new HappyPath());
        }

        @Override
        public void resetState() {
            mock.reset();
        }

        @Override
        public void close() {}
    }
}
