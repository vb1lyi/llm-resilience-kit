package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * Placeholder that fails on purpose: proves a chaosTest failure is reported but does not fail the build (ADR-004).
 * Remove in Stage 3.
 */
class ChaosSuiteWiringChaosIT {

    @Test
    void deliberateFailureDoesNotFailTheBuild() {
        fail("Deliberate failure: chaosTest failures must not fail the build. Remove in Stage 3.");
    }
}
