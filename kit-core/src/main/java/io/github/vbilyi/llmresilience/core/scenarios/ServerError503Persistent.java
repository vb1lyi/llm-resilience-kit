package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import java.time.Duration;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpResponse;

/**
 * {@code SERVER_ERROR_503_PERSISTENT}: every chat completion fails with HTTP 503.
 *
 * <p><b>Slow by design</b> - it runs a SUT's whole retry policy to exhaustion - so it is not part of
 * {@link BuiltInScenarios#catalog()}; see {@link BuiltInScenarios#nightly()}.
 */
public final class ServerError503Persistent implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "SERVER_ERROR_503_PERSISTENT";

    /** The longest a user should wait for a reply during an outage. */
    public static final Duration REPLY_LIMIT = Duration.ofMinutes(2);

    /** Creates the scenario; it is stateless. */
    public ServerError503Persistent() {}

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
        return "During a provider outage every request fails for minutes or hours. The user should be told quickly; "
                + "a long silent retry loop is as bad as an error.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions()).respond(overloaded());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The user is told the assistant is unavailable, within " + REPLY_LIMIT.toMinutes()
                        + " minutes, instead of seeing an error or waiting for all retries.",
                Expectation.noRawError(), Expectation.finishesWithin(REPLY_LIMIT));
    }

    /** Long enough for a retry policy of 10 retries with backoff capped at 3 minutes (about 22 minutes). */
    @Override
    public Duration timeBudget() {
        return Duration.ofMinutes(25);
    }

    static HttpResponse overloaded() {
        return OpenAiResponses.error(503)
                .message("The server is overloaded or not ready yet.")
                .code("service_unavailable")
                .toResponse();
    }
}
