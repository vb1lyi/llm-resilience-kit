package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;

/**
 * {@code FINISH_REASON_LENGTH}: a well-formed completion whose text stops mid-sentence, with
 * {@code finish_reason: "length"} - the model ran into its output token limit.
 */
public final class FinishReasonLength implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "FINISH_REASON_LENGTH";

    /** The answer, cut off mid-sentence. */
    public static final String TRUNCATED_ANSWER = "There are three things you need to do. First, open the settings and";

    /** Creates the scenario; it is stateless. */
    public FinishReasonLength() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Layer layer() {
        return Layer.SEMANTIC;
    }

    @Override
    public String rationale() {
        return "Long answers hit max_tokens (or the context window) and stop mid-sentence. The API reports it only "
                + "through finish_reason; a client that ignores the field presents half an answer as the whole one.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions())
                .respond(OpenAiResponses.text(TRUNCATED_ANSWER).finishReason("length").toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The user can tell the answer is incomplete (a notice, a continuation, or an "
                        + "error) - the cut-off text alone is not presented as the answer.",
                Expectation.notShownAsComplete(TRUNCATED_ANSWER), Expectation.noRawError());
    }
}
