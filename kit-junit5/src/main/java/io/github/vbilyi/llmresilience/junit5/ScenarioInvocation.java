package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import io.github.vbilyi.llmresilience.core.sut.ChatDriver;
import io.github.vbilyi.llmresilience.junit5.ScenarioOutcome.Recovery;
import io.github.vbilyi.llmresilience.junit5.ScenarioOutcome.Status;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.opentest4j.TestAbortedException;

/**
 * One invocation of a {@code @LlmChaosTest} method for one scenario. JUnit registers it as an extension for that
 * invocation only, so its fields are the invocation's state.
 *
 * <ol>
 *   <li>before: reset the environment, arm the scenario with a fresh marker;</li>
 *   <li>the test method runs, with the scenario, a driver and the marker as parameters;</li>
 *   <li>after: run the recovery probe and record the outcome.</li>
 * </ol>
 */
final class ScenarioInvocation implements TestTemplateInvocationContext, BeforeEachCallback,
        AfterTestExecutionCallback, AfterEachCallback, ParameterResolver {

    private final ChaosScenario scenario;
    private final ChaosEnvironment environment;
    private final ChaosRunRecorder recorder;
    private final boolean probeRecovery;

    private RunMarker run;
    private ChatDriver driver;
    private long startedNanos;
    private Duration duration = Duration.ZERO;
    private boolean testMethodRan;
    private boolean recorded;
    private Status status = Status.PASSED;
    private String failure = "";

    ScenarioInvocation(ChaosScenario scenario, ChaosEnvironment environment, ChaosRunRecorder recorder,
            boolean probeRecovery) {
        this.scenario = scenario;
        this.environment = environment;
        this.recorder = recorder;
        this.probeRecovery = probeRecovery;
    }

    // ---- TestTemplateInvocationContext ----

    @Override
    public String getDisplayName(int invocationIndex) {
        return scenario.id();
    }

    @Override
    public List<Extension> getAdditionalExtensions() {
        return List.of(this);
    }

    // ---- lifecycle ----

    @Override
    public void beforeEach(ExtensionContext context) {
        try {
            environment.resetState();
            run = RunMarker.random();
            scenario.arm(environment.mockServer(), run);
            driver = new ChatDriver(environment, scenario, run);
        } catch (RuntimeException e) {
            // The scenario must still show up in the run - as failed, not as missing.
            record(context, Status.FAILED, "Could not reset the environment or arm the scenario: " + e,
                    Recovery.NOT_PROBED);
            throw e;
        }
        startedNanos = System.nanoTime();
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        testMethodRan = true;
        duration = Duration.ofNanos(System.nanoTime() - startedNanos);
        Optional<Throwable> thrown = context.getExecutionException();
        if (thrown.isPresent()) {
            status = thrown.get() instanceof TestAbortedException ? Status.ABORTED : Status.FAILED;
            failure = String.valueOf(thrown.get().getMessage());
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (recorded) {
            return; // beforeEach above failed and has recorded that
        }
        if (!testMethodRan) {
            // A @BeforeEach method of the test class failed: the scenario was armed but never exercised.
            String cause = context.getExecutionException().map(Throwable::toString).orElse("unknown reason");
            record(context, Status.FAILED, "The test method did not run: " + cause, Recovery.NOT_PROBED);
            return;
        }
        Optional<String> notRecovered = Optional.empty();
        Recovery recovery = Recovery.NOT_PROBED;
        if (probeRecovery && scenario.layer() != Layer.CONTROL && RecoveryProbe.possible(environment)) {
            notRecovered = RecoveryProbe.failure(environment);
            recovery = notRecovered.isPresent() ? Recovery.NOT_RECOVERED : Recovery.RECOVERED;
        }
        record(context, status, failure, recovery);

        if (notRecovered.isPresent()) {
            throw new AssertionError("The SUT did not recover after " + scenario.id()
                    + ": a normal message afterwards failed - " + notRecovered.get());
        }
    }

    private void record(ExtensionContext context, Status status, String failure, Recovery recovery) {
        String test = context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName();
        recorder.record(new ScenarioOutcome(scenario, test, status, failure, duration, recovery,
                driver == null ? List.of() : driver.results()));
        recorded = true;
    }

    // ---- parameters ----

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == ChaosScenario.class || type == ChatDriver.class || type == RunMarker.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        if (type == ChaosScenario.class) {
            return scenario;
        }
        return type == ChatDriver.class ? driver : run;
    }
}
