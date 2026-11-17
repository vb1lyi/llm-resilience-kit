package io.github.vbilyi.llmresilience.junit5.fixtures;

import io.github.vbilyi.llmresilience.core.sut.ChatReply;
import java.time.Duration;

/** A fake SUT that crashes while handling its first message: that call and every later one cannot connect. */
public final class DiesAfterFirstMessageEnvironment extends FakeEnvironment {

    @Override
    public ChatReply chat(String userMessage, Duration timeout) {
        throw new IllegalStateException("Connection refused");
    }
}
