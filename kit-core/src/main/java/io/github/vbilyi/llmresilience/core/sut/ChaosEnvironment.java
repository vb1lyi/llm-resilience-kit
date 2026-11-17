package io.github.vbilyi.llmresilience.core.sut;

import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;
import java.time.Duration;
import org.mockserver.client.MockServerClient;

/**
 * A running system under test together with the MockServer that plays its LLM.
 *
 * <p>Implementations are written once per SUT. They need a public no-argument constructor that returns only when
 * the environment is ready; the kit creates one instance per test run, shares it between all test classes and
 * {@linkplain #close() closes} it at the end.
 */
public interface ChaosEnvironment extends AutoCloseable {

    /**
     * The client of the MockServer the SUT sends its LLM requests to.
     *
     * @return the client, for arming scenarios and reading recorded requests
     */
    MockServerClient mockServer();

    /**
     * Every scenario that can be run against this SUT: usually the built-in ones plus SUT-specific ones.
     *
     * @return the catalog that scenario ids in tests are looked up in
     */
    ScenarioCatalog scenarios();

    /**
     * Brings the environment back to its starting state before a test: clears the mock (expectations and recorded
     * requests) and undoes whatever earlier tests changed in the SUT, e.g. database rows.
     */
    void resetState();

    /**
     * Sends one chat message the way a real user's client does and waits for the reply.
     *
     * @param userMessage the text the user types
     * @param timeout how long to wait for the reply
     * @return what the user got back, whatever its status
     * @throws ChatTimeoutException if no reply arrived within {@code timeout}
     */
    ChatReply chat(String userMessage, Duration timeout);

    /** Stops the SUT and everything started with it. */
    @Override
    void close();
}
