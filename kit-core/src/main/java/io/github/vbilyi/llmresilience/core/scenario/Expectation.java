package io.github.vbilyi.llmresilience.core.scenario;

import java.time.Duration;
import java.util.Objects;

/**
 * One declarative check of how a resilient SUT should behave in a scenario. Expectations are data only; they are
 * evaluated against an observed chat result by the assertion layer (Stage 7).
 */
public sealed interface Expectation {

    /**
     * A one-line, human-readable form for reports.
     *
     * @return e.g. {@code "honors Retry-After (2 s) if it retries"}
     */
    String describe();

    /**
     * The user receives exactly this text.
     *
     * @param text the expected answer
     * @return the expectation
     */
    static Expectation userSees(String text) {
        return new UserSees(text);
    }

    /**
     * The user gets a readable message: no HTTP 500, no empty reply, no raw framework error body.
     *
     * @return the expectation
     */
    static Expectation noRawError() {
        return new NoRawError();
    }

    /**
     * If the SUT retries, every gap between attempts is at least the server's {@code Retry-After}.
     *
     * @param seconds the {@code Retry-After} value the scenario sends
     * @return the expectation
     */
    static Expectation honorsRetryAfter(int seconds) {
        return new HonorsRetryAfter(Duration.ofSeconds(seconds));
    }

    /**
     * The SUT makes at most this many LLM calls for one user message.
     *
     * @param max the call budget
     * @return the expectation
     */
    static Expectation llmCallsAtMost(int max) {
        return new LlmCallsAtMost(max);
    }

    /**
     * The user has a reply - an answer or a readable error - within this time.
     *
     * @param limit the longest acceptable wait
     * @return the expectation
     */
    static Expectation finishesWithin(Duration limit) {
        return new FinishesWithin(limit);
    }

    /**
     * The user is not shown exactly this cut-off text, with nothing added, as if it were the complete answer.
     *
     * @param truncatedText the text the model was cut off at
     * @return the expectation
     */
    static Expectation notShownAsComplete(String truncatedText) {
        return new NotShownAsComplete(truncatedText);
    }

    /**
     * The scenario leaves the SUT's database unchanged.
     *
     * @return the expectation
     */
    static Expectation noDbChanges() {
        return new NoDbChanges();
    }

    /**
     * The row count of one table changes by exactly {@code delta}.
     *
     * @param table the table name
     * @param delta expected change, e.g. {@code 1} for one inserted row
     * @return the expectation
     */
    static Expectation dbRowDelta(String table, int delta) {
        return new DbRowDelta(table, delta);
    }

    /**
     * See {@link Expectation#userSees(String)}.
     *
     * @param text the expected answer
     */
    record UserSees(String text) implements Expectation {

        /**
         * Validates the components.
         *
         * @param text the expected answer
         */
        public UserSees {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String describe() {
            return "user sees \"" + text + "\"";
        }
    }

    /** See {@link Expectation#noRawError()}. */
    record NoRawError() implements Expectation {

        @Override
        public String describe() {
            return "user sees a readable message, not a raw error";
        }
    }

    /**
     * See {@link Expectation#honorsRetryAfter(int)}.
     *
     * @param retryAfter the {@code Retry-After} the scenario sends
     */
    record HonorsRetryAfter(Duration retryAfter) implements Expectation {

        @Override
        public String describe() {
            return "honors Retry-After (" + retryAfter.toSeconds() + " s) if it retries";
        }
    }

    /**
     * See {@link Expectation#llmCallsAtMost(int)}.
     *
     * @param max the call budget
     */
    record LlmCallsAtMost(int max) implements Expectation {

        /**
         * Validates the components.
         *
         * @param max the call budget, at least 1
         */
        public LlmCallsAtMost {
            if (max < 1) {
                throw new IllegalArgumentException("max must be >= 1: " + max);
            }
        }

        @Override
        public String describe() {
            return "at most " + max + " LLM call(s)";
        }
    }

    /**
     * See {@link Expectation#finishesWithin(Duration)}.
     *
     * @param limit the longest acceptable wait
     */
    record FinishesWithin(Duration limit) implements Expectation {

        /**
         * Validates the components.
         *
         * @param limit a positive duration
         */
        public FinishesWithin {
            Objects.requireNonNull(limit, "limit");
            if (limit.isZero() || limit.isNegative()) {
                throw new IllegalArgumentException("limit must be positive: " + limit);
            }
        }

        @Override
        public String describe() {
            return "user has a reply within " + limit.toSeconds() + " s";
        }
    }

    /**
     * See {@link Expectation#notShownAsComplete(String)}.
     *
     * @param truncatedText the text the model was cut off at
     */
    record NotShownAsComplete(String truncatedText) implements Expectation {

        /**
         * Validates the components.
         *
         * @param truncatedText the text the model was cut off at
         */
        public NotShownAsComplete {
            Objects.requireNonNull(truncatedText, "truncatedText");
        }

        @Override
        public String describe() {
            return "cut-off answer is not shown as if it were complete";
        }
    }

    /** See {@link Expectation#noDbChanges()}. */
    record NoDbChanges() implements Expectation {

        @Override
        public String describe() {
            return "no database changes";
        }
    }

    /**
     * See {@link Expectation#dbRowDelta(String, int)}.
     *
     * @param table the table name
     * @param delta the expected change in row count
     */
    record DbRowDelta(String table, int delta) implements Expectation {

        /**
         * Validates the components.
         *
         * @param table the table name
         * @param delta the expected change in row count
         */
        public DbRowDelta {
            Objects.requireNonNull(table, "table");
        }

        @Override
        public String describe() {
            return "rows in " + table + " change by " + (delta >= 0 ? "+" : "") + delta;
        }
    }
}
