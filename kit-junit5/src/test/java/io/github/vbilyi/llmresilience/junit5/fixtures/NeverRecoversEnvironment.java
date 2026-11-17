package io.github.vbilyi.llmresilience.junit5.fixtures;

import io.github.vbilyi.llmresilience.core.sut.ChatReply;
import java.time.Duration;

/** A fake SUT that stays broken once any LLM call has failed - what the recovery probe exists to catch. */
public final class NeverRecoversEnvironment extends FakeEnvironment {

    private boolean broken;

    @Override
    public ChatReply chat(String userMessage, Duration timeout) {
        if (broken) {
            return new ChatReply(500, RAW_ERROR);
        }
        ChatReply reply = super.chat(userMessage, timeout);
        broken = reply.status() != 200;
        return reply;
    }
}
