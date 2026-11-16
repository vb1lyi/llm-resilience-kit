package io.github.vbilyi.llmresilience.core.scenario;

/** Where in the LLM call a scenario's failure happens. Used to group scenarios in the catalog and the scorecard. */
public enum Layer {

    /** No failure: the baseline that proves the harness works (e.g. {@code HAPPY_PATH}). */
    CONTROL,

    /** Network and HTTP status level: timeouts, dropped connections, 429, 5xx. */
    TRANSPORT,

    /** The response is not what the OpenAI protocol promises: malformed JSON, missing fields, wrong content type. */
    PROTOCOL,

    /** The response is well-formed but its content is problematic: empty, truncated, refused, filtered. */
    SEMANTIC,

    /** Tool calling goes wrong: unknown tools, invalid arguments, endless tool loops. */
    TOOLS,

    /** The API changed: new or renamed fields, unknown enum values, version differences. */
    DRIFT
}
