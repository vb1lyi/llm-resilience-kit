package io.github.vbilyi.llmresilience.junit5.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import io.github.vbilyi.llmresilience.core.sut.ChatDriver;
import io.github.vbilyi.llmresilience.core.sut.ChatResult;
import io.github.vbilyi.llmresilience.junit5.LlmChaosSuite;
import io.github.vbilyi.llmresilience.junit5.LlmChaosTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;

/**
 * Test classes that {@code LlmChaosExtensionTest} runs through the Jupiter engine. Several fail on purpose, so the
 * build excludes this package from the normal test run.
 */
public final class Fixtures {

    private Fixtures() {}

    /** HAPPY_PATH passes, RATE_LIMIT_429 fails (the fake SUT answers 500). */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class TwoScenariosById {

        @LlmChaosTest(scenarios = {"HAPPY_PATH", "RATE_LIMIT_429"})
        void userGetsHttp200(ChaosScenario scenario, ChatDriver chat, RunMarker run) {
            ChatResult result = chat.send("Hello");

            assertThat(result.userMessage()).isEqualTo("Hello [" + run.value() + "]");
            assertThat(result.llmCalls()).isEqualTo(1);
            assertThat(result.status()).as(scenario.id()).isEqualTo(200);
        }
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class WholeLayer {

        @LlmChaosTest(layers = Layer.TRANSPORT)
        void runs(ChaosScenario scenario) {
            assertThat(scenario.layer()).isEqualTo(Layer.TRANSPORT);
        }
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class WholeCatalog {

        @LlmChaosTest(all = true)
        void runs(ChaosScenario scenario) {}
    }

    /** An id plus its own layer: the scenario must not run twice. */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class IdAndLayerOverlap {

        @LlmChaosTest(scenarios = "RATE_LIMIT_429_TRANSIENT", layers = Layer.TRANSPORT)
        void runs(ChaosScenario scenario) {}
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class UnknownId {

        @LlmChaosTest(scenarios = "RATE_LIMIT_492")
        void neverRuns(ChaosScenario scenario) {}
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class NothingSelected {

        @LlmChaosTest
        void neverRuns(ChaosScenario scenario) {}
    }

    public static class NoSuiteAnnotation {

        @LlmChaosTest(all = true)
        void neverRuns(ChaosScenario scenario) {}
    }

    /** Plain tests of a suite class get the environment and its mock; no scenario is armed for them. */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class PlainTests {

        @Test
        void environmentByItsOwnType(FakeEnvironment environment, MockServerClient mock) {
            assertThat(mock).isSameAs(environment.mockServer());
        }

        @Test
        void environmentByTheInterface(ChaosEnvironment environment) {
            assertThat(environment).isInstanceOf(FakeEnvironment.class);
        }
    }

    /** The fake SUT stays broken after the 429, so the recovery probe fails although the test method passes. */
    @LlmChaosSuite(environment = NeverRecoversEnvironment.class)
    public static class SutDoesNotRecover {

        @LlmChaosTest(scenarios = "RATE_LIMIT_429")
        void passesByItself(ChatDriver chat) {
            chat.send("Hello");
        }
    }

    @LlmChaosSuite(environment = NeverRecoversEnvironment.class, recoveryProbe = false)
    public static class ProbeSwitchedOff {

        @LlmChaosTest(scenarios = "RATE_LIMIT_429")
        void passes(ChatDriver chat) {
            chat.send("Hello");
        }
    }

    /** HAPPY_PATH itself fails: the harness is invalid. */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class HappyPathFails {

        @LlmChaosTest(scenarios = "HAPPY_PATH")
        void fails(ChatDriver chat) {
            assertThat(chat.send("Hello").body()).isEqualTo("something else");
        }
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class Aborted {

        @LlmChaosTest(scenarios = "RATE_LIMIT_429")
        void aborts() {
            Assumptions.abort("not today");
        }
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class AllCombinedWithIds {

        @LlmChaosTest(all = true, scenarios = "RATE_LIMIT_492")
        void neverRuns(ChaosScenario scenario) {}
    }

    /** A lifecycle method of the test class fails, so the test method never runs. */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class BeforeEachFails {

        @BeforeEach
        void prepare() {
            throw new IllegalStateException("fixture data missing");
        }

        @LlmChaosTest(scenarios = "RATE_LIMIT_429")
        void neverRuns(ChatDriver chat) {}
    }

    /** The environment cannot be reset (see {@link FakeEnvironment#FAIL_RESET}): arming never happens. */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class ResetFails {

        @LlmChaosTest(scenarios = "RATE_LIMIT_429")
        void neverRuns(ChatDriver chat) {}
    }

    /** The SUT is gone after the scenario: even the probe's chat call throws. */
    @LlmChaosSuite(environment = DiesAfterFirstMessageEnvironment.class)
    public static class SutDiesDuringScenario {

        @LlmChaosTest(scenarios = "RATE_LIMIT_429")
        void passesByItself(ChatDriver chat) {
            chat.send("Hello");
        }
    }

    /** Two classes like this must never be inside a test at the same time: they share one environment. */
    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class DetectsOverlapA {

        static final AtomicInteger INSIDE = new AtomicInteger();

        @LlmChaosTest(all = true)
        void runsAlone(ChatDriver chat) throws InterruptedException {
            assertThat(INSIDE.incrementAndGet()).as("tests running at the same time").isEqualTo(1);
            Thread.sleep(40);
            chat.send("Hello");
            INSIDE.decrementAndGet();
        }
    }

    @LlmChaosSuite(environment = FakeEnvironment.class)
    public static class DetectsOverlapB {

        @LlmChaosTest(all = true)
        void runsAlone(ChatDriver chat) throws InterruptedException {
            assertThat(DetectsOverlapA.INSIDE.incrementAndGet()).as("tests running at the same time").isEqualTo(1);
            Thread.sleep(40);
            chat.send("Hello");
            DetectsOverlapA.INSIDE.decrementAndGet();
        }
    }

    @LlmChaosSuite(environment = BrokenEnvironment.class)
    public static class EnvironmentCannotStartA {

        @LlmChaosTest(all = true)
        void neverRuns(ChaosScenario scenario) {}
    }

    @LlmChaosSuite(environment = BrokenEnvironment.class)
    public static class EnvironmentCannotStartB {

        @LlmChaosTest(all = true)
        void neverRuns(ChaosScenario scenario) {}
    }
}
