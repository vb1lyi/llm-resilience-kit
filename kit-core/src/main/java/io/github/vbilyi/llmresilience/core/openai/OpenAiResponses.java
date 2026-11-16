package io.github.vbilyi.llmresilience.core.openai;

import org.mockserver.model.HttpResponse;

/**
 * Entry point for building OpenAI API responses for MockServer.
 *
 * <pre>{@code
 * mock.when(OpenAiRequests.chatCompletions())
 *     .respond(OpenAiResponses.text("Hello!").toResponse());
 *
 * mock.when(OpenAiRequests.chatCompletions())
 *     .respond(OpenAiResponses.error(429).code("rate_limit_exceeded").retryAfter(2).toResponse());
 * }</pre>
 */
public final class OpenAiResponses {

    /** Embedding size of {@code text-embedding-ada-002}, the Spring AI default embedding model. */
    public static final int ADA_002_DIMENSIONS = 1536;

    private OpenAiResponses() {}

    /**
     * Starts an empty chat completion (no content, no tool calls).
     *
     * @return a new builder
     */
    public static ChatCompletionBuilder chatCompletion() {
        return new ChatCompletionBuilder();
    }

    /**
     * Starts a chat completion whose assistant message is plain text.
     *
     * @param content the assistant's answer
     * @return a new builder
     */
    public static ChatCompletionBuilder text(String content) {
        return chatCompletion().content(content);
    }

    /**
     * Starts a chat completion that asks the client to call one tool.
     *
     * @param name the function name
     * @param argumentsJson the arguments as a JSON string (may be invalid on purpose)
     * @return a new builder
     */
    public static ChatCompletionBuilder toolCall(String name, String argumentsJson) {
        return chatCompletion().toolCall(name, argumentsJson);
    }

    /**
     * Starts an OpenAI-format error response.
     *
     * @param status the HTTP status, e.g. 429 or 503
     * @return a new builder
     */
    public static ErrorResponseBuilder error(int status) {
        return new ErrorResponseBuilder(status);
    }

    /**
     * The error OpenAI returns when the requests-per-minute limit is hit.
     *
     * @param retryAfterSeconds value of the {@code Retry-After} header
     * @return a builder preset with status 429, type {@code requests} and code {@code rate_limit_exceeded}
     */
    public static ErrorResponseBuilder rateLimited(int retryAfterSeconds) {
        return error(429)
                .message("Rate limit reached for gpt-4o on requests per min (RPM): Limit 500.")
                .type("requests")
                .code("rate_limit_exceeded")
                .retryAfter(retryAfterSeconds);
    }

    /**
     * Starts an embeddings response.
     *
     * @param dimensions vector length, e.g. {@link #ADA_002_DIMENSIONS}
     * @return a new builder
     */
    public static EmbeddingsBuilder embeddings(int dimensions) {
        return new EmbeddingsBuilder(dimensions);
    }

    /**
     * A response with {@code Content-Type: application/json} and the body exactly as given. The body is not
     * validated, so it can be truncated or otherwise broken on purpose.
     *
     * @param status the HTTP status
     * @param body the raw body
     * @return the response, ready for {@code mock.when(...).respond(...)}
     */
    public static HttpResponse jsonResponse(int status, String body) {
        return HttpResponse.response()
                .withStatusCode(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
