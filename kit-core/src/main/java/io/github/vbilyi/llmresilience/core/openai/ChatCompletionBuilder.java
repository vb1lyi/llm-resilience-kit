package io.github.vbilyi.llmresilience.core.openai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.mockserver.model.HttpResponse;

/**
 * Builds an OpenAI {@code chat.completion} response: assistant text, tool calls, or both.
 *
 * <p>Defaults produce a valid response: model {@code gpt-4o}, {@code finish_reason} {@code "stop"} (or
 * {@code "tool_calls"} when tool calls were added), and small token usage. Tool call arguments are passed through as
 * raw strings, so they can deliberately be invalid JSON or carry wrong types.
 */
public final class ChatCompletionBuilder {

    private String id = "chatcmpl-llm-resilience";
    private String model = "gpt-4o";
    private long created = 1_762_300_000L;
    private String content;
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private String finishReason;
    private int promptTokens = 42;
    private int completionTokens = 9;

    ChatCompletionBuilder() {}

    /**
     * Sets the assistant's text.
     *
     * @param text the message content; {@code null} for a tool-call-only message
     * @return this builder
     */
    public ChatCompletionBuilder content(String text) {
        this.content = text;
        return this;
    }

    /**
     * Adds a tool call with id {@code call_<n>} (n = 1, 2, ...).
     *
     * @param name the function name, e.g. {@code listOwners}; may name a tool the SUT does not have
     * @param argumentsJson the arguments exactly as OpenAI sends them: a JSON document <em>as a string</em>,
     *     e.g. {@code "{}"}; may be invalid on purpose
     * @return this builder
     */
    public ChatCompletionBuilder toolCall(String name, String argumentsJson) {
        toolCalls.add(new ToolCall("call_" + (toolCalls.size() + 1), name, argumentsJson));
        return this;
    }

    /**
     * Overrides {@code finish_reason}, e.g. {@code "length"} or {@code "content_filter"}.
     *
     * @param reason the finish reason
     * @return this builder
     */
    public ChatCompletionBuilder finishReason(String reason) {
        this.finishReason = reason;
        return this;
    }

    /**
     * Sets token usage; {@code total_tokens} is their sum.
     *
     * @param prompt prompt tokens
     * @param completion completion tokens
     * @return this builder
     */
    public ChatCompletionBuilder usage(int prompt, int completion) {
        this.promptTokens = prompt;
        this.completionTokens = completion;
        return this;
    }

    /**
     * Sets the model name echoed in the response.
     *
     * @param model e.g. {@code gpt-4o}
     * @return this builder
     */
    public ChatCompletionBuilder model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Sets the response id.
     *
     * @param id e.g. {@code chatcmpl-123}
     * @return this builder
     */
    public ChatCompletionBuilder id(String id) {
        this.id = id;
        return this;
    }

    /**
     * Builds the JSON body.
     *
     * @return the response body as a JSON string
     */
    public String json() {
        ObjectNode message = Json.object().put("role", "assistant").put("content", content);
        if (!toolCalls.isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCall call : toolCalls) {
                ObjectNode c = calls.addObject().put("id", call.id()).put("type", "function");
                c.putObject("function").put("name", call.name()).put("arguments", call.argumentsJson());
            }
        }

        ObjectNode root = Json.object()
                .put("id", id)
                .put("object", "chat.completion")
                .put("created", created)
                .put("model", model);
        ObjectNode choice = root.putArray("choices").addObject().put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", finishReason != null ? finishReason : toolCalls.isEmpty() ? "stop" : "tool_calls");
        root.putObject("usage")
                .put("prompt_tokens", promptTokens)
                .put("completion_tokens", completionTokens)
                .put("total_tokens", promptTokens + completionTokens);
        return Json.write(root);
    }

    /**
     * Builds an HTTP 200 MockServer response with this body.
     *
     * @return the response, ready for {@code mock.when(...).respond(...)}
     */
    public HttpResponse toResponse() {
        return OpenAiResponses.jsonResponse(200, json());
    }

    private record ToolCall(String id, String name, String argumentsJson) {}
}
