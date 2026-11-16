package io.github.vbilyi.llmresilience.core.scenarios;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;

/**
 * {@code DRIFT_RENAMED_FIELD}: a completion in which {@code message.content} arrives as {@code message.text} and
 * {@code finish_reason} as {@code stop_reason}. The answer is there, but not where the client looks.
 */
public final class DriftRenamedField implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "DRIFT_RENAMED_FIELD";

    /** Creates the scenario; it is stateless. */
    public DriftRenamedField() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Layer layer() {
        return Layer.DRIFT;
    }

    @Override
    public String rationale() {
        return "\"OpenAI-compatible\" gateways and self-hosted models do not always match the schema, and a "
                + "provider switch or API version bump can move a field. The client then parses a response with no content.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions()).respond(OpenAiResponses.jsonResponse(200, bodyWithRenamedFields()));
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The user gets a readable message - not an empty reply and not a raw error.",
                Expectation.noRawError(), Expectation.llmCallsAtMost(1));
    }

    static String bodyWithRenamedFields() {
        return JsonEdit.edit(OpenAiResponses.text(HappyPath.ANSWER).json(), root -> {
            ObjectNode choice = (ObjectNode) root.path("choices").path(0);
            choice.set("stop_reason", choice.remove("finish_reason"));
            ObjectNode message = (ObjectNode) choice.path("message");
            message.set("text", message.remove("content"));
        });
    }
}
