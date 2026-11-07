package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Placeholder: proves the module compiles and runs unit tests on Java 21. Replace with real tests. */
class BuildWiringTest {

    @Test
    void runsOnJava21() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
