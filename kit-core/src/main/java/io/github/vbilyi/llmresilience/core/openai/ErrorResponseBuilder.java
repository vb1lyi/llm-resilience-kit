package io.github.vbilyi.llmresilience.core.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpResponse;

/**
 * Builds an OpenAI error response: an HTTP status plus
 * {@code {"error": {"message", "type", "param", "code"}}}, optionally with a {@code Retry-After} header.
 */
public final class ErrorResponseBuilder {

    private final int status;
    private String message;
    private String type;
    private String code;
    private Integer retryAfterSeconds;

    ErrorResponseBuilder(int status) {
        this.status = status;
        this.message = "The server returned HTTP " + status + ".";
        this.type = status >= 500 ? "server_error" : "invalid_request_error";
    }

    /**
     * Sets the human-readable error message.
     *
     * @param message the message
     * @return this builder
     */
    public ErrorResponseBuilder message(String message) {
        this.message = message;
        return this;
    }

    /**
     * Sets the error type, e.g. {@code requests} or {@code server_error}.
     *
     * @param type the error type
     * @return this builder
     */
    public ErrorResponseBuilder type(String type) {
        this.type = type;
        return this;
    }

    /**
     * Sets the machine-readable error code, e.g. {@code rate_limit_exceeded}.
     *
     * @param code the error code
     * @return this builder
     */
    public ErrorResponseBuilder code(String code) {
        this.code = code;
        return this;
    }

    /**
     * Adds a {@code Retry-After} header.
     *
     * @param seconds delay the client should wait before retrying
     * @return this builder
     */
    public ErrorResponseBuilder retryAfter(int seconds) {
        this.retryAfterSeconds = seconds;
        return this;
    }

    /**
     * Builds the JSON body.
     *
     * @return the error body as a JSON string
     */
    public String json() {
        ObjectNode root = Json.object();
        ObjectNode error = root.putObject("error").put("message", message).put("type", type);
        error.putNull("param");
        error.put("code", code);
        return Json.write(root);
    }

    /**
     * Builds the MockServer response with the status, body and optional {@code Retry-After} header.
     *
     * @return the response, ready for {@code mock.when(...).respond(...)}
     */
    public HttpResponse toResponse() {
        HttpResponse response = OpenAiResponses.jsonResponse(status, json());
        return retryAfterSeconds == null ? response : response.withHeader("Retry-After", retryAfterSeconds.toString());
    }
}
