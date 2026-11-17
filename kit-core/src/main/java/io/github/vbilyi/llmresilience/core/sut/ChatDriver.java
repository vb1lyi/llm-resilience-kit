package io.github.vbilyi.llmresilience.core.sut;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.mockserver.model.LogEventRequestAndResponse;

/**
 * Sends chat messages to the SUT for one armed scenario and records the evidence.
 *
 * <p>For every message the driver
 * <ol>
 *   <li>adds the {@link RunMarker}, so the mock can tell this run's LLM requests from late ones (ADR-007);</li>
 *   <li>waits no longer than the scenario's {@link ChaosScenario#timeBudget() time budget} - a SUT may have no
 *       timeout of its own;</li>
 *   <li>reads back from the mock when the SUT called the LLM.</li>
 * </ol>
 * A driver belongs to one test invocation and is not thread-safe.
 */
public final class ChatDriver {

    /** MockServer's log timestamps, e.g. {@code 2026-11-02 18:51:00.485} (spike Q9). */
    private static final DateTimeFormatter MOCK_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final ChaosEnvironment environment;
    private final ChaosScenario scenario;
    private final RunMarker run;
    private final List<ChatResult> results = new ArrayList<>();

    /**
     * Creates a driver for a scenario that has been armed with the same marker.
     *
     * @param environment the running SUT and its mock
     * @param scenario the armed scenario; supplies the time budget
     * @param run the marker the scenario was armed with
     */
    public ChatDriver(ChaosEnvironment environment, ChaosScenario scenario, RunMarker run) {
        this.environment = environment;
        this.scenario = scenario;
        this.run = run;
    }

    /**
     * Sends one message and waits for the reply or for the time budget to run out. Never throws for a SUT that is
     * slow or failing: that is recorded in the result.
     *
     * @param userMessage what the user types, without a marker
     * @return what was observed
     */
    public ChatResult send(String userMessage) {
        String marked = run.mark(userMessage);
        Duration timeout = scenario.timeBudget();

        ChatReply reply = null;
        long t0 = System.nanoTime();
        try {
            reply = environment.chat(marked, timeout);
        } catch (ChatTimeoutException e) {
            // recorded below as timedOut
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - t0);

        ChatResult result = reply == null
                ? new ChatResult(marked, true, 0, "", duration, llmCallTimes())
                : new ChatResult(marked, false, reply.status(), reply.body(), duration, llmCallTimes());
        results.add(result);
        return result;
    }

    /**
     * Every result of this driver so far, in the order the messages were sent.
     *
     * @return an immutable copy
     */
    public List<ChatResult> results() {
        return List.copyOf(results);
    }

    private List<LocalDateTime> llmCallTimes() {
        return Arrays.stream(environment.mockServer().retrieveRecordedRequestsAndResponses(run.chatCompletions()))
                .map(LogEventRequestAndResponse::getTimestamp)
                .map(timestamp -> LocalDateTime.parse(timestamp, MOCK_TIMESTAMP))
                .sorted()
                .toList();
    }
}
