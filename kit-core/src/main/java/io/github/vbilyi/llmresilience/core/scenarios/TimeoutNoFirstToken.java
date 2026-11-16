package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.mockserver.client.MockServerClient;

/**
 * {@code TIMEOUT_NO_FIRST_TOKEN}: the LLM accepts the request and then sends nothing for {@link #DELAY}, after
 * which a valid answer arrives. A SUT with a sensible read timeout has given up long before.
 */
public final class TimeoutNoFirstToken implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "TIMEOUT_NO_FIRST_TOKEN";

    /** How long the mock stays silent before it answers. */
    public static final Duration DELAY = Duration.ofSeconds(90);

    /** The longest a user should wait for a reply. */
    public static final Duration REPLY_LIMIT = Duration.ofSeconds(60);

    /** Creates the scenario; it is stateless. */
    public TimeoutNoFirstToken() {}

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
        return "An overloaded model, a stuck load balancer or a lost packet leaves the connection open but silent. "
                + "Without a read timeout the user's request - and a server thread - hangs for as long as the LLM does.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions())
                .respond(OpenAiResponses.text(HappyPath.ANSWER).toResponse()
                        .withDelay(TimeUnit.SECONDS, DELAY.toSeconds()));
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The SUT gives up within " + REPLY_LIMIT.toSeconds()
                        + " s and tells the user, instead of hanging until the LLM answers.",
                Expectation.finishesWithin(REPLY_LIMIT), Expectation.noRawError());
    }

    /** Longer than {@link #DELAY}, so a SUT without a timeout is seen to wait for the whole delay. */
    @Override
    public Duration timeBudget() {
        return DELAY.plusSeconds(30);
    }
}
