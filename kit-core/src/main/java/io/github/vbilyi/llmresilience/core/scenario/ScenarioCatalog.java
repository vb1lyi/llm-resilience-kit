package io.github.vbilyi.llmresilience.core.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** An ordered, immutable registry of scenarios with unique ids. */
public final class ScenarioCatalog {

    private final Map<String, ChaosScenario> byId;

    private ScenarioCatalog(List<ChaosScenario> scenarios) {
        Map<String, ChaosScenario> map = new LinkedHashMap<>();
        for (ChaosScenario s : scenarios) {
            if (!s.id().matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("Scenario id must be UPPER_SNAKE_CASE: " + s.id());
            }
            if (map.putIfAbsent(s.id(), s) != null) {
                throw new IllegalArgumentException("Duplicate scenario id: " + s.id());
            }
        }
        this.byId = map;
    }

    /**
     * Creates a catalog; order is preserved.
     *
     * @param scenarios scenarios with unique ids
     * @return the catalog
     */
    public static ScenarioCatalog of(ChaosScenario... scenarios) {
        return new ScenarioCatalog(List.of(scenarios));
    }

    /**
     * Returns a new catalog with more scenarios appended, e.g. the built-in ones plus those written for one SUT.
     *
     * @param more scenarios whose ids are not in this catalog yet
     * @return the combined catalog; this one is unchanged
     */
    public ScenarioCatalog plus(ChaosScenario... more) {
        List<ChaosScenario> combined = new ArrayList<>(byId.values());
        combined.addAll(List.of(more));
        return new ScenarioCatalog(combined);
    }

    /**
     * All scenarios in registration order.
     *
     * @return an immutable list
     */
    public List<ChaosScenario> all() {
        return List.copyOf(byId.values());
    }

    /**
     * Looks a scenario up by id.
     *
     * @param id e.g. {@code RATE_LIMIT_429}
     * @return the scenario, if registered
     */
    public Optional<ChaosScenario> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Looks a scenario up by id, failing if it is unknown.
     *
     * @param id e.g. {@code RATE_LIMIT_429}
     * @return the scenario
     * @throws NoSuchElementException if no scenario has this id
     */
    public ChaosScenario get(String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("Unknown scenario: " + id + "; known: " + byId.keySet()));
    }

    /**
     * Scenarios of one layer, in registration order.
     *
     * @param layer the layer
     * @return an immutable list, possibly empty
     */
    public List<ChaosScenario> byLayer(Layer layer) {
        return byId.values().stream().filter(s -> s.layer() == layer).toList();
    }
}
