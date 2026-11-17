package io.github.vbilyi.llmresilience.junit5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.vbilyi.llmresilience.junit5.ScenarioOutcome.Recovery;
import io.github.vbilyi.llmresilience.junit5.ScenarioOutcome.Status;
import io.github.vbilyi.llmresilience.junit5.fixtures.BrokenEnvironment;
import io.github.vbilyi.llmresilience.junit5.fixtures.FakeEnvironment;
import io.github.vbilyi.llmresilience.junit5.fixtures.Fixtures;
import io.github.vbilyi.llmresilience.junit5.fixtures.RecordingListener;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;

/**
 * Runs the fixture classes through the real Jupiter engine, against a fake environment (in-JVM MockServer, no SUT),
 * and checks what the extension did. Each {@code run(...)} is one complete test run with its own root store.
 */
class LlmChaosExtensionTest {

    @BeforeEach
    void forgetEarlierRuns() {
        RecordingListener.RUNS.clear();
        FakeEnvironment.resetCounters();
        BrokenEnvironment.ATTEMPTS.set(0);
    }

    // ---- selection ----

    @Test
    void runsTheMethodOncePerScenarioIdAndNamesInvocationsAfterTheScenario() {
        EngineExecutionResults results = run(Fixtures.TwoScenariosById.class);

        assertThat(invocationNames(results)).containsExactly("HAPPY_PATH", "RATE_LIMIT_429");
        results.testEvents().assertStatistics(stats -> stats.started(2).succeeded(1).failed(1));
    }

    @Test
    void selectsAllScenariosOfALayer() {
        assertThat(invocationNames(run(Fixtures.WholeLayer.class)))
                .containsExactly("RATE_LIMIT_429", "RATE_LIMIT_429_TRANSIENT");
    }

    @Test
    void selectsTheWholeCatalogInCatalogOrder() {
        assertThat(invocationNames(run(Fixtures.WholeCatalog.class)))
                .containsExactly("HAPPY_PATH", "RATE_LIMIT_429", "RATE_LIMIT_429_TRANSIENT", "TOOL_UNKNOWN_NAME");
    }

    @Test
    void idsComeFirstAndNoScenarioRunsTwice() {
        assertThat(invocationNames(run(Fixtures.IdAndLayerOverlap.class)))
                .containsExactly("RATE_LIMIT_429_TRANSIENT", "RATE_LIMIT_429");
    }

    @Test
    void anUnknownIdFailsAndListsTheKnownIds() {
        assertThat(failureMessages(run(Fixtures.UnknownId.class))).singleElement().asString()
                .contains("RATE_LIMIT_492")
                .contains("RATE_LIMIT_429");
    }

    @Test
    void selectingNothingFails() {
        assertThat(failureMessages(run(Fixtures.NothingSelected.class))).singleElement().asString()
                .contains("selects no scenario");
    }

    @Test
    void aClassWithoutTheSuiteAnnotationFails() {
        assertThat(failureMessages(run(Fixtures.NoSuiteAnnotation.class))).singleElement().asString()
                .contains("@LlmChaosSuite");
    }

    // ---- environment ----

    @Test
    void oneEnvironmentIsSharedByAllClassesOfARunAndClosedAtTheEnd() {
        run(Fixtures.TwoScenariosById.class, Fixtures.WholeLayer.class, Fixtures.PlainTests.class);

        assertThat(FakeEnvironment.STARTED).hasValue(1);
        assertThat(FakeEnvironment.CLOSED).hasValue(1);
    }

    @Test
    void theEnvironmentIsResetBeforeEveryInvocation() {
        run(Fixtures.WholeCatalog.class);

        assertThat(FakeEnvironment.RESETS).hasValue(4);
    }

    @Test
    void plainTestsOfASuiteGetTheEnvironmentAndItsMock() {
        run(Fixtures.PlainTests.class).testEvents().assertStatistics(stats -> stats.started(2).succeeded(2));
    }

    @Test
    void anEnvironmentThatCannotStartIsTriedOnceAndFailsEveryClassWithTheCause() {
        EngineExecutionResults results =
                run(Fixtures.EnvironmentCannotStartA.class, Fixtures.EnvironmentCannotStartB.class);

        assertThat(BrokenEnvironment.ATTEMPTS).hasValue(1);
        assertThat(failureMessages(results)).hasSize(2)
                .allSatisfy(message -> assertThat(message).contains("Cannot start the environment"));
    }

    // ---- outcomes ----

    @Test
    void outcomesReachTheListenerWithStatusEvidenceAndFailureMessage() {
        run(Fixtures.TwoScenariosById.class);

        ChaosRun finished = onlyRun();
        assertThat(finished.outcomes()).extracting(o -> o.scenario().id(), ScenarioOutcome::status)
                .containsExactly(
                        tuple("HAPPY_PATH", Status.PASSED),
                        tuple("RATE_LIMIT_429", Status.FAILED));

        ScenarioOutcome rateLimit = finished.outcomes().get(1);
        assertThat(rateLimit.test()).isEqualTo("TwoScenariosById.userGetsHttp200");
        assertThat(rateLimit.failure()).contains("RATE_LIMIT_429").contains("500");
        assertThat(rateLimit.chats()).singleElement().satisfies(chat -> {
            assertThat(chat.status()).isEqualTo(500);
            assertThat(chat.body()).isEqualTo(FakeEnvironment.RAW_ERROR);
            assertThat(chat.llmCalls()).isEqualTo(1);
        });
        assertThat(finished.harnessValid()).isTrue(); // a failed chaos scenario is a finding, not a broken harness
    }

    @Test
    void aFailedControlScenarioMakesTheHarnessInvalid() {
        run(Fixtures.HappyPathFails.class);

        assertThat(onlyRun().harnessValid()).isFalse();
    }

    @Test
    void anAbortedTestIsRecordedAsAborted() {
        run(Fixtures.Aborted.class);

        assertThat(onlyRun().outcomes()).singleElement()
                .satisfies(outcome -> assertThat(outcome.status()).isEqualTo(Status.ABORTED));
    }

    // ---- recovery probe ----

    @Test
    void chaosScenariosAreProbedForRecoveryAndControlScenariosAreNot() {
        run(Fixtures.TwoScenariosById.class);

        assertThat(onlyRun().outcomes()).extracting(ScenarioOutcome::recovery)
                .containsExactly(Recovery.NOT_PROBED, Recovery.RECOVERED);
    }

    @Test
    void aSutThatDoesNotRecoverFailsTheInvocationAlthoughTheTestMethodPassed() {
        EngineExecutionResults results = run(Fixtures.SutDoesNotRecover.class);

        assertThat(failureMessages(results)).singleElement().asString()
                .contains("did not recover after RATE_LIMIT_429")
                .contains("HTTP 500");
        assertThat(onlyRun().outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(Status.PASSED);
            assertThat(outcome.recovery()).isEqualTo(Recovery.NOT_RECOVERED);
        });
    }

    @Test
    void theProbeCanBeSwitchedOff() {
        run(Fixtures.ProbeSwitchedOff.class).testEvents().assertStatistics(stats -> stats.started(1).succeeded(1));

        assertThat(onlyRun().outcomes()).singleElement()
                .satisfies(outcome -> assertThat(outcome.recovery()).isEqualTo(Recovery.NOT_PROBED));
    }

    // ---- helpers ----

    private static EngineExecutionResults run(Class<?>... fixtures) {
        List<ClassSelector> selectors = Arrays.stream(fixtures).map(c -> selectClass(c)).toList();
        return EngineTestKit.engine("junit-jupiter").selectors(selectors).execute();
    }

    private static List<String> invocationNames(EngineExecutionResults results) {
        return results.testEvents().started().stream().map(e -> e.getTestDescriptor().getDisplayName()).toList();
    }

    /** Messages of everything that failed: test invocations and their containers (configuration errors). */
    private static List<String> failureMessages(EngineExecutionResults results) {
        return results.allEvents().failed().stream()
                .map(Event::getPayload)
                .map(payload -> payload.map(TestExecutionResult.class::cast).orElseThrow())
                .map(result -> String.valueOf(result.getThrowable().orElseThrow().getMessage()))
                .toList();
    }

    private static ChaosRun onlyRun() {
        assertThat(RecordingListener.RUNS).hasSize(1);
        return RecordingListener.RUNS.getFirst();
    }
}
