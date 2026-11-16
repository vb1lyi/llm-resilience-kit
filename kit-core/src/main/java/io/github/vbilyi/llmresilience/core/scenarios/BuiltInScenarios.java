package io.github.vbilyi.llmresilience.core.scenarios;

import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;

/** The scenarios that ship with the kit. */
public final class BuiltInScenarios {

    private BuiltInScenarios() {}

    /**
     * The built-in scenarios that are fast enough for every build; {@code HAPPY_PATH} first. They speak the OpenAI
     * protocol only; scenarios that need a SUT's own tool names are added with {@link ScenarioCatalog#plus}.
     *
     * @return the catalog
     */
    public static ScenarioCatalog catalog() {
        return ScenarioCatalog.of(
                new HappyPath(),
                new RateLimit429(),
                new RateLimit429Transient(),
                new ServerError503(),
                new ConnectionDrop(),
                new TimeoutNoFirstToken(),
                new MalformedJsonBody(),
                new FinishReasonLength(),
                new ToolUnknownName(),
                new DriftUnknownFields(),
                new DriftRenamedField());
    }

    /**
     * Scenarios that are slow by design (tens of minutes against a SUT with patient retry defaults): for a nightly
     * or manual run, never for every build.
     *
     * @return the catalog
     */
    public static ScenarioCatalog nightly() {
        return ScenarioCatalog.of(new ServerError503Persistent());
    }
}
