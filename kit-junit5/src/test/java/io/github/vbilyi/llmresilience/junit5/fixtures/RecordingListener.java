package io.github.vbilyi.llmresilience.junit5.fixtures;

import io.github.vbilyi.llmresilience.junit5.ChaosRun;
import io.github.vbilyi.llmresilience.junit5.ChaosRunListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registered through META-INF/services: keeps every finished run so the tests can inspect it. */
public final class RecordingListener implements ChaosRunListener {

    public static final List<ChaosRun> RUNS = new CopyOnWriteArrayList<>();

    @Override
    public void runFinished(ChaosRun run) {
        RUNS.add(run);
    }
}
