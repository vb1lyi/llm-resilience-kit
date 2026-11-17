package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.sut.ChatResult;
import java.time.Duration;
import java.util.List;

/**
 * How one invocation of a test method went for one scenario.
 *
 * @param scenario the scenario that was armed
 * @param test the test, as {@code ClassName.methodName}
 * @param status whether the test method passed
 * @param failure the failure message; empty if the test passed
 * @param duration how long the test method ran
 * @param recovery whether the SUT answered a normal message afterwards
 * @param chats what the test's {@code ChatDriver} observed, in order
 */
public record ScenarioOutcome(ChaosScenario scenario, String test, Status status, String failure, Duration duration,
        Recovery recovery, List<ChatResult> chats) {

    /** The verdict of the test method. */
    public enum Status {
        /** The SUT behaved as the test expects. */
        PASSED,
        /** An assertion failed or the test threw: for a chaos scenario, a finding. */
        FAILED,
        /** The test was aborted by an assumption. */
        ABORTED
    }

    /** The result of the recovery probe that follows a scenario. */
    public enum Recovery {
        /** The SUT answered a normal message after the scenario. */
        RECOVERED,
        /** The SUT did not answer a normal message after the scenario. */
        NOT_RECOVERED,
        /** No probe was made: a control scenario, the probe is switched off, or there is no {@code HAPPY_PATH}. */
        NOT_PROBED
    }

    /**
     * Defensively copies the components.
     *
     * @param scenario the scenario
     * @param test the test name
     * @param status the verdict
     * @param failure the failure message, or empty
     * @param duration the test duration
     * @param recovery the probe result
     * @param chats the chat results
     */
    public ScenarioOutcome {
        chats = List.copyOf(chats);
    }
}
