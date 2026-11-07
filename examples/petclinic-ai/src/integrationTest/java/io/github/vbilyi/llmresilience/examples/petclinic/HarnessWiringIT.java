package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Placeholder: proves the integrationTest (harness) suite is wired. Replaced by EnvironmentSmokeIT in Stage 2. */
class HarnessWiringIT {

    @Test
    void harnessSuiteRuns() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
