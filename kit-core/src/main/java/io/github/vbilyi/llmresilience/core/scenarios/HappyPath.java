package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;

/**
 * {@code HAPPY_PATH}: the LLM behaves perfectly. The control scenario: if a SUT fails it, the harness is broken
 * and no other result can be trusted (ADR-004).
 */
public final class HappyPath implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "HAPPY_PATH";

    /** The answer the mocked LLM gives. Deliberately says nothing about any particular SUT. */
    public static final String ANSWER = "This is the answer from the mocked model.";

    /** Creates the scenario; it is stateless. */
    public HappyPath() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Layer layer() {
        return Layer.CONTROL;
    }

    @Override
    public String rationale() {
        return "Baseline: a valid completion and valid embeddings. Proves the SUT, the mock and the test wiring work.";
    }

    /** Answers the run's chat completions with {@link #ANSWER}, and every embeddings call with a valid vector. */
    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions()).respond(OpenAiResponses.text(ANSWER).toResponse());
        mock.when(OpenAiRequests.embeddings())
                .respond(OpenAiResponses.embeddings(OpenAiResponses.ADA_002_DIMENSIONS).toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The user gets the model's answer after one LLM call; nothing is written.",
                Expectation.userSees(ANSWER), Expectation.llmCallsAtMost(1), Expectation.noDbChanges());
    }
}
