package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;

/**
 * {@code TOOL_UNKNOWN_NAME}: the model asks for a tool that does not exist ({@value #TOOL_NAME}); if the client
 * reports that back, the model then answers normally.
 */
public final class ToolUnknownName implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "TOOL_UNKNOWN_NAME";

    /** A tool name no SUT is expected to have. */
    public static final String TOOL_NAME = "purgeArchivedRecords";

    /** Creates the scenario; it is stateless. */
    public ToolUnknownName() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Layer layer() {
        return Layer.TOOLS;
    }

    @Override
    public String rationale() {
        return "Models hallucinate tool names, and a prompt or model update can leave them calling a tool that was "
                + "renamed or removed. The API does not validate tool names in responses.";
    }

    /** One call to the unknown tool, then {@link HappyPath#ANSWER}. */
    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions(), Times.exactly(1))
                .respond(OpenAiResponses.toolCall(TOOL_NAME, "{}").toResponse());
        mock.when(run.chatCompletions()).respond(OpenAiResponses.text(HappyPath.ANSWER).toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The SUT does not crash: it tells the model the tool is unknown, or tells the user "
                        + "something readable. Nothing is written.",
                Expectation.noRawError(), Expectation.llmCallsAtMost(2), Expectation.noDbChanges());
    }
}
