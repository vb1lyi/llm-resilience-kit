package io.github.vbilyi.llmresilience.junit5;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Collects outcomes during a run. Lives in JUnit's root store, which closes it when the run ends. */
final class ChaosRunRecorder implements AutoCloseable {

    private final List<ScenarioOutcome> outcomes = new ArrayList<>();

    synchronized void record(ScenarioOutcome outcome) {
        outcomes.add(outcome);
    }

    @Override
    public synchronized void close() {
        if (outcomes.isEmpty()) {
            return;
        }
        ChaosRun run = new ChaosRun(outcomes);
        for (ChaosRunListener listener : ServiceLoader.load(ChaosRunListener.class)) {
            listener.runFinished(run);
        }
    }
}
