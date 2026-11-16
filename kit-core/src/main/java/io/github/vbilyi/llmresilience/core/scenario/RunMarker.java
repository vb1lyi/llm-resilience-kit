package io.github.vbilyi.llmresilience.core.scenario;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import java.util.UUID;
import org.mockserver.model.HttpRequest;

/**
 * A unique token that ties LLM requests to the one test run that caused them (ADR-007).
 *
 * <p>The test puts the marker into the user's chat message ({@link #mark(String)}). The SUT sends that message to
 * the LLM in every request of the run - including tool follow-up turns - so the mock can tell this run's requests
 * from late requests of an earlier run (a SUT may keep retrying or looping after its client has gone). Scenarios arm
 * only {@link #chatCompletions()}; tests count only those.
 *
 * @param value the token: letters, digits and dashes, e.g. {@code llmrk-3f9a1c2e7b40}
 */
public record RunMarker(String value) {

    /**
     * Validates the token.
     *
     * @param value letters, digits and dashes, at least 8 characters
     */
    public RunMarker {
        if (value == null || !value.matches("[A-Za-z0-9-]{8,}")) {
            throw new IllegalArgumentException("A run marker is 8+ letters, digits or dashes: " + value);
        }
    }

    /**
     * Creates a marker that no other run has used.
     *
     * @return a new marker, {@code llmrk-} plus 12 random hex digits
     */
    public static RunMarker random() {
        return new RunMarker("llmrk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    /**
     * Adds the marker to a user message.
     *
     * @param userMessage what the user asks, e.g. {@code "How many owners do we have?"}
     * @return the message followed by the marker in square brackets
     */
    public String mark(String userMessage) {
        return userMessage + " [" + value + "]";
    }

    /**
     * The chat completion requests that belong to this run.
     *
     * @return a MockServer matcher for arming and for retrieving recorded requests
     */
    public HttpRequest chatCompletions() {
        return OpenAiRequests.chatCompletionsContaining(value);
    }
}
