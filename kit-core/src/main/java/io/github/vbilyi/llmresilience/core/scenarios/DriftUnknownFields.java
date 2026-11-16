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
 * {@code DRIFT_UNKNOWN_FIELDS}: a valid completion with fields the client has never seen, at every level
 * (top level, choice, message, usage).
 */
public final class DriftUnknownFields implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "DRIFT_UNKNOWN_FIELDS";

    /** Creates the scenario; it is stateless. */
    public DriftUnknownFields() {}

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
        return "The API grows: system_fingerprint, service_tier, refusal, annotations and the usage detail objects "
                + "were all added to chat completions after launch. A client that rejects unknown fields breaks on such a day.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions()).respond(OpenAiResponses.jsonResponse(200, bodyWithUnknownFields()));
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("Unknown fields are ignored; the user gets the answer.",
                Expectation.userSees(HappyPath.ANSWER), Expectation.llmCallsAtMost(1));
    }

    static String bodyWithUnknownFields() {
        return JsonEdit.edit(OpenAiResponses.text(HappyPath.ANSWER).json(), root -> {
            root.put("system_fingerprint", "fp_drift");
            root.put("service_tier", "default");
            root.putObject("x_future_feature").put("enabled", true);
            ObjectNode choice = (ObjectNode) root.path("choices").path(0);
            choice.putNull("logprobs");
            ObjectNode message = (ObjectNode) choice.path("message");
            message.putNull("refusal");
            message.putArray("annotations");
            ((ObjectNode) root.path("usage")).putObject("completion_tokens_details").put("reasoning_tokens", 0);
        });
    }
}
