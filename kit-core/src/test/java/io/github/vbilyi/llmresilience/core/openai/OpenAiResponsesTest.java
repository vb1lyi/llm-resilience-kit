package io.github.vbilyi.llmresilience.core.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockserver.model.HttpResponse;

/** Builders produce the OpenAI response shapes captured from a real Spring AI client (spike Q8, Q11). */
class OpenAiResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void textCompletionHasTheChatCompletionShape() throws Exception {
        JsonNode json = MAPPER.readTree(OpenAiResponses.text("Hello!").usage(10, 3).json());

        assertThat(json.path("object").asText()).isEqualTo("chat.completion");
        assertThat(json.path("model").asText()).isEqualTo("gpt-4o");
        JsonNode choice = json.path("choices").get(0);
        assertThat(choice.path("index").asInt()).isZero();
        assertThat(choice.path("message").path("role").asText()).isEqualTo("assistant");
        assertThat(choice.path("message").path("content").asText()).isEqualTo("Hello!");
        assertThat(choice.path("message").has("tool_calls")).isFalse();
        assertThat(choice.path("finish_reason").asText()).isEqualTo("stop");
        assertThat(json.path("usage").path("prompt_tokens").asInt()).isEqualTo(10);
        assertThat(json.path("usage").path("completion_tokens").asInt()).isEqualTo(3);
        assertThat(json.path("usage").path("total_tokens").asInt()).isEqualTo(13);
    }

    @Test
    void toolCallHasNullContentAndFunctionCallsWithStringArguments() throws Exception {
        JsonNode json = MAPPER.readTree(OpenAiResponses.toolCall("listOwners", "{}")
                .toolCall("addPetToOwner", "{\"ownerId\": 1}")
                .json());

        JsonNode message = json.path("choices").get(0).path("message");
        assertThat(message.has("content")).isTrue();
        assertThat(message.path("content").isNull()).isTrue();
        assertThat(json.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");

        JsonNode first = message.path("tool_calls").get(0);
        assertThat(first.path("id").asText()).isEqualTo("call_1");
        assertThat(first.path("type").asText()).isEqualTo("function");
        assertThat(first.path("function").path("name").asText()).isEqualTo("listOwners");
        assertThat(first.path("function").path("arguments").isTextual()).isTrue(); // a JSON document as a string
        assertThat(message.path("tool_calls").get(1).path("id").asText()).isEqualTo("call_2");
    }

    @Test
    void toolCallArgumentsArePassedThroughVerbatimEvenWhenInvalid() throws Exception {
        String broken = "{\"ownerId\": \"seven\", ";

        JsonNode json = MAPPER.readTree(OpenAiResponses.toolCall("addPetToOwner", broken).json());

        assertThat(json.at("/choices/0/message/tool_calls/0/function/arguments").asText()).isEqualTo(broken);
    }

    @Test
    void finishReasonCanBeOverridden() throws Exception {
        JsonNode json = MAPPER.readTree(OpenAiResponses.text("cut off").finishReason("length").json());

        assertThat(json.at("/choices/0/finish_reason").asText()).isEqualTo("length");
    }

    @Test
    void completionResponseIsHttp200Json() {
        HttpResponse response = OpenAiResponses.text("Hi").toResponse();

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getFirstHeader("Content-Type")).isEqualTo("application/json");
        assertThat(response.getBodyAsString()).contains("\"Hi\"");
    }

    @Test
    void jsonResponseSendsTheBodyVerbatimEvenWhenItIsNotJson() {
        HttpResponse response = OpenAiResponses.jsonResponse(200, "{\"choices\": [");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getFirstHeader("Content-Type")).isEqualTo("application/json");
        assertThat(response.getBodyAsString()).isEqualTo("{\"choices\": [");
    }

    @Test
    void rateLimitedIs429WithRetryAfterAndOpenAiErrorBody() throws Exception {
        HttpResponse response = OpenAiResponses.rateLimited(2).toResponse();

        assertThat(response.getStatusCode()).isEqualTo(429);
        assertThat(response.getFirstHeader("Retry-After")).isEqualTo("2");
        JsonNode error = MAPPER.readTree(response.getBodyAsString()).path("error");
        assertThat(error.path("type").asText()).isEqualTo("requests");
        assertThat(error.path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.has("param")).isTrue();
        assertThat(error.path("param").isNull()).isTrue();
    }

    @Test
    void errorWithoutRetryAfterHasNoHeaderAndAStatusBasedType() throws Exception {
        HttpResponse response = OpenAiResponses.error(503).toResponse();

        assertThat(response.getStatusCode()).isEqualTo(503);
        assertThat(response.containsHeader("Retry-After")).isFalse();
        assertThat(MAPPER.readTree(response.getBodyAsString()).at("/error/type").asText()).isEqualTo("server_error");
    }

    @Test
    void embeddingsHaveTheRequestedDimensionsAndUnitLength() throws Exception {
        JsonNode json = MAPPER.readTree(OpenAiResponses.embeddings(OpenAiResponses.ADA_002_DIMENSIONS).count(2).json());

        assertThat(json.path("object").asText()).isEqualTo("list");
        assertThat(json.path("data")).hasSize(2);
        JsonNode first = json.path("data").get(0);
        assertThat(first.path("object").asText()).isEqualTo("embedding");
        assertThat(first.path("embedding")).hasSize(1536);
        double sumOfSquares = 0;
        for (JsonNode component : first.path("embedding")) {
            sumOfSquares += component.asDouble() * component.asDouble();
        }
        assertThat(sumOfSquares).isCloseTo(1.0, offset(1e-9));
        assertThat(json.path("data").get(1).path("index").asInt()).isEqualTo(1);
    }

    @Test
    void embeddingsRejectZeroDimensions() {
        assertThatThrownBy(() -> OpenAiResponses.embeddings(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
