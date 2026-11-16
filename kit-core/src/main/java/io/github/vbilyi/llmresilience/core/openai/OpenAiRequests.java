package io.github.vbilyi.llmresilience.core.openai;

import static org.mockserver.model.JsonPathBody.jsonPath;

import org.mockserver.model.HttpRequest;

/**
 * MockServer request matchers for the OpenAI endpoints a Spring AI client calls.
 *
 * <p><b>Telling conversation turns apart.</b> A SUT with chat memory sends earlier messages — including old tool
 * results — in every request. Matchers that must tell turn 1 from turn 2 therefore look only at the <em>last</em>
 * message ({@code $.messages[-1:]}), never at "the body contains X" (ADR-006).
 *
 * <p><b>Telling test runs apart.</b> {@link #chatCompletionsContaining(String)} matches only requests that carry a
 * per-run marker in one of their messages (ADR-007).
 */
public final class OpenAiRequests {

    /** Default chat completions path (Spring AI {@code spring.ai.openai.chat.completions-path}). */
    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** Default embeddings path (Spring AI {@code spring.ai.openai.embedding.embeddings-path}). */
    public static final String EMBEDDINGS_PATH = "/v1/embeddings";

    private OpenAiRequests() {}

    /**
     * Any chat completion request.
     *
     * @return a matcher for {@code POST /v1/chat/completions}
     */
    public static HttpRequest chatCompletions() {
        return HttpRequest.request().withMethod("POST").withPath(CHAT_COMPLETIONS_PATH);
    }

    /**
     * A chat completion request whose last message has the given role — e.g. {@code "tool"} for the follow-up turn
     * after the client ran a tool, or {@code "user"} for a fresh user message.
     *
     * @param role {@code user}, {@code tool}, {@code assistant} or {@code system}
     * @return a matcher on {@code $.messages[-1:]}
     */
    public static HttpRequest chatCompletionsWhereLastMessageIs(String role) {
        if (!role.matches("[a-z]+")) {
            throw new IllegalArgumentException("Unexpected role: " + role);
        }
        return chatCompletions().withBody(jsonPath("$.messages[-1:][?(@.role == '" + role + "')]"));
    }

    /**
     * A chat completion request in which at least one message contains the given text. Used with a per-run marker
     * (see {@code RunMarker}): the marker sits in the user's message, which the client re-sends on every turn.
     *
     * @param text letters, digits and dashes only
     * @return a matcher on {@code $.messages[?(@.content contains '...')]}
     */
    public static HttpRequest chatCompletionsContaining(String text) {
        if (!text.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Unexpected marker text: " + text);
        }
        return chatCompletions().withBody(jsonPath("$.messages[?(@.content contains '" + text + "')]"));
    }

    /**
     * Any embeddings request.
     *
     * @return a matcher for {@code POST /v1/embeddings}
     */
    public static HttpRequest embeddings() {
        return HttpRequest.request().withMethod("POST").withPath(EMBEDDINGS_PATH);
    }
}
