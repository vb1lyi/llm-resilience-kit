package io.github.vbilyi.llmresilience.junit5.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;
import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429;
import io.github.vbilyi.llmresilience.core.scenarios.RateLimit429Transient;
import io.github.vbilyi.llmresilience.core.scenarios.ToolUnknownName;
import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import io.github.vbilyi.llmresilience.core.sut.ChatReply;
import io.github.vbilyi.llmresilience.core.sut.ChatTimeoutException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;

/**
 * A stand-in for a real environment: an in-JVM MockServer plus the most naive "SUT" possible - it forwards the
 * user's message to the LLM once, returns the model's text, and turns any LLM error into a raw HTTP 500.
 */
public class FakeEnvironment implements ChaosEnvironment {

    /** What the fake SUT shows when the LLM call fails - the same shape as Spring Boot's default error body. */
    public static final String RAW_ERROR = "{\"status\":500,\"error\":\"Internal Server Error\"}";

    public static final AtomicInteger STARTED = new AtomicInteger();
    public static final AtomicInteger CLOSED = new AtomicInteger();
    public static final AtomicInteger RESETS = new AtomicInteger();

    /** Set by a test to make {@link #resetState()} fail, as a broken database connection would. */
    public static volatile boolean FAIL_RESET;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClientAndServer mock = ClientAndServer.startClientAndServer(0);
    private final HttpClient http = HttpClient.newHttpClient();

    public FakeEnvironment() {
        STARTED.incrementAndGet();
    }

    public static void resetCounters() {
        STARTED.set(0);
        CLOSED.set(0);
        RESETS.set(0);
        FAIL_RESET = false;
    }

    @Override
    public MockServerClient mockServer() {
        return mock;
    }

    @Override
    public ScenarioCatalog scenarios() {
        return ScenarioCatalog.of(new HappyPath(), new RateLimit429(), new RateLimit429Transient(), new ToolUnknownName());
    }

    @Override
    public void resetState() {
        RESETS.incrementAndGet();
        if (FAIL_RESET) {
            throw new IllegalStateException("cannot reset the database");
        }
        mock.reset();
    }

    @Override
    public ChatReply chat(String userMessage, Duration timeout) {
        try {
            ObjectNode body = MAPPER.createObjectNode().put("model", "gpt-4o");
            body.putArray("messages").addObject().put("role", "user").put("content", userMessage);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + mock.getLocalPort() + OpenAiRequests.CHAT_COMPLETIONS_PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new ChatReply(500, RAW_ERROR);
            }
            JsonNode content = MAPPER.readTree(response.body()).at("/choices/0/message/content");
            return content.isTextual() ? new ChatReply(200, content.asText()) : new ChatReply(500, RAW_ERROR);
        } catch (HttpTimeoutException e) {
            throw new ChatTimeoutException(timeout, e);
        } catch (IOException e) {
            return new ChatReply(500, RAW_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        CLOSED.incrementAndGet();
        mock.stop();
    }
}
