package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.scenario.Layer;
import java.util.List;

/**
 * Every scenario outcome of one test run, in execution order.
 *
 * @param outcomes one entry per test invocation
 */
public record ChaosRun(List<ScenarioOutcome> outcomes) {

    /**
     * Defensively copies the components.
     *
     * @param outcomes the outcomes
     */
    public ChaosRun {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * Whether the results can be trusted: no control scenario (e.g. {@code HAPPY_PATH}) failed. If a control
     * scenario fails, the harness is broken and the other outcomes say nothing about the SUT (ADR-004).
     *
     * @return {@code false} if any control-layer outcome is {@code FAILED}
     */
    public boolean harnessValid() {
        return outcomes.stream()
                .noneMatch(o -> o.scenario().layer() == Layer.CONTROL && o.status() == ScenarioOutcome.Status.FAILED);
    }
}
