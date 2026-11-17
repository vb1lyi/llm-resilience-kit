package io.github.vbilyi.llmresilience.core.sut;

import java.time.Duration;

/** The SUT did not reply to a chat message within the time the test was prepared to wait. */
public final class ChatTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param timeout how long the caller waited
     * @param cause the client's own timeout error
     */
    public ChatTimeoutException(Duration timeout, Throwable cause) {
        super("No reply from the SUT within " + timeout.toSeconds() + " s", cause);
    }
}
