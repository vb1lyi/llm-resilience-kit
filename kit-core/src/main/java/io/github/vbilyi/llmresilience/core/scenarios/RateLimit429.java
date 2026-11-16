package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;

/** {@code RATE_LIMIT_429}: every chat completion is rate-limited (HTTP 429 with {@code Retry-After}). */
public final class RateLimit429 implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "RATE_LIMIT_429";

    /** The {@code Retry-After} value sent with every 429, in seconds. */
    public static final int RETRY_AFTER_SECONDS = 2;

    /** Creates the scenario; it is stateless. */
    public RateLimit429() {}

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
        return "Hosted LLM APIs enforce requests- and tokens-per-minute limits; under load, or on a shared key, "
                + "clients get HTTP 429 with a Retry-After header for minutes at a time.";
    }

    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions())
                .respond(OpenAiResponses.rateLimited(RETRY_AFTER_SECONDS).toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The user is told to try again later instead of seeing an error; "
                        + "any retry waits for Retry-After.",
                Expectation.noRawError(), Expectation.honorsRetryAfter(RETRY_AFTER_SECONDS));
    }
}
