package io.github.vbilyi.llmresilience.junit5;

/**
 * Receives the outcomes when a test run ends - the hook for report writers.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}: list the class in
 * {@code META-INF/services/io.github.vbilyi.llmresilience.junit5.ChaosRunListener}.
 */
public interface ChaosRunListener {

    /**
     * Called once, after the last test of the run, if at least one {@code @LlmChaosTest} invocation ran.
     *
     * @param run the collected outcomes
     */
    void runFinished(ChaosRun run);
}
