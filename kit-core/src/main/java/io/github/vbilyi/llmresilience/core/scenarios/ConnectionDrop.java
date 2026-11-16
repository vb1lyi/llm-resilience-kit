package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpError;

/**
 * {@code CONNECTION_DROP}: the first chat completion's connection is closed without a response, the next one
 * succeeds.
 *
 * <p>Only one drop: clients usually retry I/O errors, and a persistent failure would run the whole retry policy
 * (see {@link ServerError503Persistent}).
 */
public final class ConnectionDrop implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "CONNECTION_DROP";

    /** Creates the scenario; it is stateless. */
    public ConnectionDrop() {}

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
        return "Connections to an LLM API die mid-request: idle keep-alive connections are reaped by proxies, pods "
                + "restart during deployments, networks blip. One retry on a fresh connection normally succeeds.";
    }

    /** One dropped connection, then {@link HappyPath#ANSWER} for every later call. */
    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions(), Times.exactly(1)).error(HttpError.error().withDropConnection(true));
        mock.when(run.chatCompletions()).respond(OpenAiResponses.text(HappyPath.ANSWER).toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The SUT retries once and the user gets the answer.",
                Expectation.userSees(HappyPath.ANSWER), Expectation.llmCallsAtMost(2));
    }
}
