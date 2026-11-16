package io.github.vbilyi.llmresilience.core.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.JsonPathBody;

/**
 * Request matchers. The last-message expression is evaluated with Jayway JsonPath — the engine MockServer itself
 * uses — against request bodies shaped like the ones captured from the SUT (spike Q8): history first.
 */
class OpenAiRequestsTest {

    // Turn 2 of a tool round trip: [history..., system, user, assistant(tool_calls), tool]
    private static final String TOOL_FOLLOW_UP = """
            {"model": "gpt-4o", "stream": false, "messages": [
              {"role": "user", "content": "\\"earlier question\\""},
              {"role": "assistant", "content": "earlier answer"},
              {"role": "system", "content": "You are a friendly AI assistant..."},
              {"role": "user", "content": "\\"list the owners\\""},
              {"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function",
                                                   "function": {"name": "listOwners", "arguments": "{}"}}]},
              {"role": "tool", "content": "[...]", "name": "listOwners", "tool_call_id": "call_1"}
            ]}""";

    // A new user message whose HISTORY contains an old tool result (global chat memory, ADR-006).
    private static final String NEW_MESSAGE_WITH_OLD_TOOL_IN_HISTORY = """
            {"model": "gpt-4o", "stream": false, "messages": [
              {"role": "tool", "content": "[...]", "name": "listOwners", "tool_call_id": "call_1"},
              {"role": "assistant", "content": "There are 10 owners."},
              {"role": "system", "content": "You are a friendly AI assistant..."},
              {"role": "user", "content": "\\"hello again\\""}
            ]}""";

    @Test
    void chatCompletionsMatchesPostToTheDefaultPath() {
        HttpRequest matcher = OpenAiRequests.chatCompletions();

        assertThat(matcher.getMethod().getValue()).isEqualTo("POST");
        assertThat(matcher.getPath().getValue()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void embeddingsMatchesPostToTheDefaultPath() {
        HttpRequest matcher = OpenAiRequests.embeddings();

        assertThat(matcher.getMethod().getValue()).isEqualTo("POST");
        assertThat(matcher.getPath().getValue()).isEqualTo("/v1/embeddings");
    }

    @Test
    void lastMessageIsToolMatchesTheToolFollowUpTurn() {
        assertThat(matches("tool", TOOL_FOLLOW_UP)).isTrue();
        assertThat(matches("user", TOOL_FOLLOW_UP)).isFalse();
    }

    @Test
    void lastMessageIsToolIgnoresToolResultsInHistory() {
        assertThat(matches("tool", NEW_MESSAGE_WITH_OLD_TOOL_IN_HISTORY)).isFalse();
        assertThat(matches("user", NEW_MESSAGE_WITH_OLD_TOOL_IN_HISTORY)).isTrue();
    }

    @Test
    void containingMatchesOnlyRequestsWhoseMessagesCarryTheMarker() {
        // The marker sits in the user message; the tool follow-up turn still carries it. Messages without content
        // (assistant tool_calls) must not break the expression.
        String ofThisRun = TOOL_FOLLOW_UP.replace("list the owners", "list the owners [llmrk-aaaa1111]");

        assertThat(selects(OpenAiRequests.chatCompletionsContaining("llmrk-aaaa1111"), ofThisRun)).isTrue();
        assertThat(selects(OpenAiRequests.chatCompletionsContaining("llmrk-bbbb2222"), ofThisRun)).isFalse();
        assertThatThrownBy(() -> OpenAiRequests.chatCompletionsContaining("x') || ('"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleMustBeAPlainLowercaseWord() {
        assertThatThrownBy(() -> OpenAiRequests.chatCompletionsWhereLastMessageIs("tool') || (true"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** MockServer semantics: a JSON path body matches when the expression selects at least one node. */
    private static boolean matches(String role, String body) {
        return selects(OpenAiRequests.chatCompletionsWhereLastMessageIs(role), body);
    }

    private static boolean selects(HttpRequest matcher, String body) {
        String expression = ((JsonPathBody) matcher.getBody()).getValue();
        List<Object> selected = JsonPath.read(body, expression);
        return !selected.isEmpty();
    }
}
