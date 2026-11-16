package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;

/**
 * {@code RATE_LIMIT_429_TRANSIENT}: the first chat completion is rate-limited, the next one succeeds. A client that
 * retries after {@code Retry-After} gets the real answer.
 */
public final class RateLimit429Transient implements ChaosScenario {

    /** Scenario id. */
    public static final String ID = "RATE_LIMIT_429_TRANSIENT";

    /** Creates the scenario; it is stateless. */
    public RateLimit429Transient() {}

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
        return "Most rate limits are brief: a burst exceeds the per-minute quota and the next request a few "
                + "seconds later goes through. Retrying is exactly what Retry-After is for.";
    }

    /** One 429, then {@link HappyPath#ANSWER} for every later call (MockServer matches in creation order). */
    @Override
    public void arm(MockServerClient mock, RunMarker run) {
        mock.when(run.chatCompletions(), Times.exactly(1))
                .respond(OpenAiResponses.rateLimited(RateLimit429.RETRY_AFTER_SECONDS).toResponse());
        mock.when(run.chatCompletions()).respond(OpenAiResponses.text(HappyPath.ANSWER).toResponse());
    }

    @Override
    public ExpectedBehavior expected() {
        return ExpectedBehavior.of("The SUT waits for Retry-After, retries once, and the user gets the answer.",
                Expectation.userSees(HappyPath.ANSWER), Expectation.honorsRetryAfter(RateLimit429.RETRY_AFTER_SECONDS));
    }
}
