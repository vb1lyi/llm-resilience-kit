package io.github.vbilyi.llmresilience.junit5.fixtures;

import java.util.concurrent.atomic.AtomicInteger;

/** An environment that cannot start. */
public final class BrokenEnvironment extends FakeEnvironment {

    public static final AtomicInteger ATTEMPTS = new AtomicInteger();

    public BrokenEnvironment() {
        ATTEMPTS.incrementAndGet();
        close();
        throw new IllegalStateException("docker is not running");
    }
}
