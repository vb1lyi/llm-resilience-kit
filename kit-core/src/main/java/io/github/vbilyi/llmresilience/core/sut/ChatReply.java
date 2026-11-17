package io.github.vbilyi.llmresilience.core.sut;

import java.util.Objects;

/**
 * What the user got back for one chat message, exactly as the SUT sent it.
 *
 * @param status the HTTP status of the SUT's reply
 * @param body the reply body the user's client would display
 */
public record ChatReply(int status, String body) {

    /**
     * Validates the components.
     *
     * @param status the HTTP status
     * @param body the reply body; empty if there was none
     */
    public ChatReply {
        Objects.requireNonNull(body, "body");
    }
}
