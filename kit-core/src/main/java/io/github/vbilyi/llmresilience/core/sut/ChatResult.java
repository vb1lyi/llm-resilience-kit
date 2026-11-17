package io.github.vbilyi.llmresilience.core.sut;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything observed for one chat message: what the user saw, how long it took, and when the SUT called the LLM.
 *
 * @param userMessage the message as sent, including the run marker
 * @param timedOut {@code true} if the SUT had not replied when the scenario's time budget ran out; {@code status}
 *     is then 0 and {@code body} empty
 * @param status the HTTP status of the SUT's reply
 * @param body what the user saw
 * @param duration wall-clock time from sending the message to the reply (or to giving up)
 * @param llmCallTimes when each of this run's chat completion requests reached the mock, oldest first, on the
 *     mock's clock - compare them with each other, not with the test's clock
 */
public record ChatResult(String userMessage, boolean timedOut, int status, String body, Duration duration,
        List<LocalDateTime> llmCallTimes) {

    /**
     * Validates and defensively copies the components.
     *
     * @param userMessage the message as sent
     * @param timedOut whether the test gave up waiting
     * @param status the HTTP status, 0 if timed out
     * @param body what the user saw, empty if timed out
     * @param duration the measured wall-clock time
     * @param llmCallTimes the LLM call times, oldest first
     */
    public ChatResult {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(duration, "duration");
        llmCallTimes = List.copyOf(llmCallTimes);
    }

    /**
     * How many chat completion requests the SUT made for this message (so far, if it timed out).
     *
     * @return the number of LLM calls
     */
    public int llmCalls() {
        return llmCallTimes.size();
    }

    /**
     * The pauses between consecutive LLM calls - the SUT's retry backoff, when it retries.
     *
     * @return one duration per pair of consecutive calls; empty for zero or one call
     */
    public List<Duration> gapsBetweenLlmCalls() {
        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i < llmCallTimes.size(); i++) {
            gaps.add(Duration.between(llmCallTimes.get(i - 1), llmCallTimes.get(i)));
        }
        return List.copyOf(gaps);
    }
}
