package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;

/**
 * {@code MALFORMED_JSON_BODY}: HTTP 200 with {@code Content-Type: application/json}, but the body is a valid chat
 * completion cut off in the middle.
 */
public final class MalformedJsonBody implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "MALFORMED_JSON_BODY";

    /** Creates the scenario; it is stateless. */
    public MalformedJsonBody() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Layer layer() {
        return Layer.PROTOCOL;
    }

    @Override
    public String rationale() {
        return "A proxy or gateway times out while relaying the response, or the provider's stream is cut: the "
                + "client gets a success status and half a JSON document.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions()).respond(OpenAiResponses.jsonResponse(200, truncatedBody()));
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The user gets a readable message, not a parser error or the raw body.",
                Expectation.noRawError());
    }

    static String truncatedBody() {
        String complete = OpenAiResponses.text(HappyPath.ANSWER).json();
        return complete.substring(0, complete.length() / 2);
    }
}
