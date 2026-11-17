package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import io.github.vbilyi.llmresilience.core.sut.ChatDriver;
import io.github.vbilyi.llmresilience.core.sut.ChatResult;
import java.util.Optional;

/**
 * After a chaos scenario: does the SUT still answer a normal message? Arms {@code HAPPY_PATH} from the environment's
 * catalog, sends one message and compares the reply with the answer that scenario promises.
 *
 * <p>"The SUT did not recover" is a finding about that scenario, and a warning about every result after it.
 */
final class RecoveryProbe {

    private RecoveryProbe() {}

    /** Whether the environment's catalog has the scenario the probe needs. */
    static boolean possible(ChaosEnvironment environment) {
        return environment.scenarios().find(HappyPath.ID).isPresent();
    }

    /**
     * Runs the probe.
     *
     * @return empty if the SUT recovered, otherwise what went wrong
     */
    static Optional<String> failure(ChaosEnvironment environment) {
        ChaosScenario happyPath = environment.scenarios().get(HappyPath.ID);
        RunMarker run = RunMarker.random();
        environment.mockServer().reset();
        happyPath.arm(environment.mockServer(), run);

        ChatResult result = new ChatDriver(environment, happyPath, run).send("Are you still there?");

        if (result.timedOut()) {
            return Optional.of("no reply within " + happyPath.timeBudget().toSeconds() + " s");
        }
        for (Expectation expectation : happyPath.expected().expectations()) {
            if (expectation instanceof Expectation.UserSees(String text) && !text.equals(result.body())) {
                return Optional.of("expected the reply \"" + text + "\" but got HTTP " + result.status() + ": "
                        + result.body());
            }
        }
        return Optional.empty();
    }
}
