package io.github.vbilyi.llmresilience.core.scenario;

import java.util.List;
import java.util.Objects;

/**
 * What a resilient SUT does in a scenario: a sentence for humans plus the declarative checks that decide it.
 *
 * @param description e.g. "The user is told to try again later; nothing is written to the database."
 * @param expectations the checks, evaluated in order (Stage 7)
 */
public record ExpectedBehavior(String description, List<Expectation> expectations) {

    /**
     * Validates and defensively copies the components.
     *
     * @param description human-readable summary
     * @param expectations at least one check
     */
    public ExpectedBehavior {
        Objects.requireNonNull(description, "description");
        expectations = List.copyOf(expectations);
        if (expectations.isEmpty()) {
            throw new IllegalArgumentException("An expected behavior needs at least one expectation");
        }
    }

    /**
     * Convenience factory.
     *
     * @param description human-readable summary
     * @param expectations at least one check
     * @return the expected behavior
     */
    public static ExpectedBehavior of(String description, Expectation... expectations) {
        return new ExpectedBehavior(description, List.of(expectations));
    }
}
