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
 * {@code SERVER_ERROR_503}: the first {@value #FAILURES} chat completions fail with HTTP 503, the next one succeeds.
 *
 * <p>The failure is short on purpose: with a SUT's shipped retry defaults a <em>persistent</em> 503 can take many
 * minutes per message (ADR-005 addendum). That variant is {@link ServerError503Persistent}.
 */
public final class ServerError503 implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "SERVER_ERROR_503";

    /** How many chat completions fail before one succeeds. */
    public static final int FAILURES = 2;

    /** Creates the scenario; it is stateless. */
    public ServerError503() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Layer layer() {
        return Layer.TRANSPORT;
    }

    @Override
    public String rationale() {
        return "LLM providers shed load with HTTP 503 (\"overloaded\") during incidents and traffic peaks; most such "
                + "errors clear within seconds, so a client that retries with backoff gets its answer.";
    }

    /** {@value #FAILURES} times 503, then {@link HappyPath#ANSWER} for every later call. */
    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions(), Times.exactly(FAILURES)).respond(ServerError503Persistent.overloaded());
        mock.when(run.chatCompletions()).respond(OpenAiResponses.text(HappyPath.ANSWER).toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The SUT retries and the user gets the answer.",
                Expectation.userSees(HappyPath.ANSWER), Expectation.llmCallsAtMost(FAILURES + 1));
    }
}
