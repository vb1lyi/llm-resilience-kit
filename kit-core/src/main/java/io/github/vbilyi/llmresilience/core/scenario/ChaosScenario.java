package io.github.vbilyi.llmresilience.core.scenario;

import java.time.Duration;
import org.mockserver.client.MockServerClient;

/**
 * One realistic LLM failure mode: how to make the mock misbehave, why that happens in production, and what a
 * resilient SUT should do about it.
 *
 * <p>Implementations are stateless and SUT-agnostic: they speak the OpenAI protocol only.
 */
public interface ChaosScenario {

    /**
     * Stable identifier used in reports and for lookup.
     *
     * @return e.g. {@code RATE_LIMIT_429} (upper snake case)
     */
    String id();

    /**
     * Where the failure happens.
     *
     * @return the layer
     */
    Layer layer();

    /**
     * The real-world cause of this failure, shown in reports.
     *
     * @return one or two sentences
     */
    String rationale();

    /**
     * Arms the mock with this scenario. The caller resets the mock before arming.
     *
     * <p>Chat completion expectations must be limited to {@link RunMarker#chatCompletions() run.chatCompletions()},
     * so that late requests of an earlier run are not answered (ADR-007). Turns within the run are told apart by
     * order: MockServer uses expectations in the order they were created.
     *
     * @param mock client of the MockServer the SUT talks to
     * @param run the marker the test puts into its user message
     */
    void arm(MockServerClient mock, RunMarker run);

    /**
     * How long a test should wait for the SUT's reply before giving up. A SUT may have no timeout of its own, so
     * the test must; scenarios that are slow by design override this.
     *
     * @return the client-side wait limit; one minute unless overridden
     */
    default Duration timeBudget() {
        return Duration.ofMinutes(1);
    }

    /**
     * What a resilient SUT does in this scenario.
     *
     * @return the expected behavior
     */
    ExpectedBehavior expected();
}
