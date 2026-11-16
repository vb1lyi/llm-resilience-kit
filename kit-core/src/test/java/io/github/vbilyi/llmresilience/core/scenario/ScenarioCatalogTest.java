package io.github.vbilyi.llmresilience.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;

class ScenarioCatalogTest {

    @Test
    void keepsRegistrationOrderAndFindsById() {
        ScenarioCatalog catalog = ScenarioCatalog.of(scenario("B_FIRST", Layer.CONTROL), scenario("A_SECOND", Layer.TOOLS));

        assertThat(catalog.all()).extracting(ChaosScenario::id).containsExactly("B_FIRST", "A_SECOND");
        assertThat(catalog.get("A_SECOND").layer()).isEqualTo(Layer.TOOLS);
        assertThat(catalog.find("MISSING")).isEmpty();
    }

    @Test
    void listsByLayer() {
        ScenarioCatalog catalog = ScenarioCatalog.of(
                scenario("ONE", Layer.TRANSPORT), scenario("TWO", Layer.TOOLS), scenario("THREE", Layer.TRANSPORT));

        assertThat(catalog.byLayer(Layer.TRANSPORT)).extracting(ChaosScenario::id).containsExactly("ONE", "THREE");
        assertThat(catalog.byLayer(Layer.DRIFT)).isEmpty();
    }

    @Test
    void unknownIdFailsWithTheKnownIds() {
        ScenarioCatalog catalog = ScenarioCatalog.of(scenario("ONE", Layer.CONTROL));

        assertThatThrownBy(() -> catalog.get("NOPE"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("NOPE")
                .hasMessageContaining("ONE");
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> ScenarioCatalog.of(scenario("SAME", Layer.CONTROL), scenario("SAME", Layer.TOOLS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SAME");
    }

    @Test
    void rejectsIdsThatAreNotUpperSnakeCase() {
        assertThatThrownBy(() -> ScenarioCatalog.of(scenario("rate-limit", Layer.TRANSPORT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plusAppendsScenariosAndLeavesTheOriginalUnchanged() {
        ScenarioCatalog builtIn = ScenarioCatalog.of(scenario("ONE", Layer.CONTROL));

        ScenarioCatalog combined = builtIn.plus(scenario("TWO", Layer.TOOLS));

        assertThat(combined.all()).extracting(ChaosScenario::id).containsExactly("ONE", "TWO");
        assertThat(builtIn.all()).extracting(ChaosScenario::id).containsExactly("ONE");
        assertThatThrownBy(() -> combined.plus(scenario("ONE", Layer.DRIFT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ONE");
    }

    @Test
    void timeBudgetDefaultsToOneMinute() {
        assertThat(scenario("ONE", Layer.CONTROL).timeBudget()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void runMarkersAreUniqueAndMarkTheUserMessage() {
        RunMarker marker = RunMarker.random();

        assertThat(marker).isNotEqualTo(RunMarker.random());
        assertThat(marker.mark("Hello")).isEqualTo("Hello [" + marker.value() + "]");
        assertThatThrownBy(() -> new RunMarker("it's")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expectedBehaviorNeedsAtLeastOneExpectation() {
        assertThatThrownBy(() -> ExpectedBehavior.of("nothing to check"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expectationsDescribeThemselves() {
        assertThat(Expectation.honorsRetryAfter(2).describe()).isEqualTo("honors Retry-After (2 s) if it retries");
        assertThat(Expectation.dbRowDelta("owners", 1).describe()).isEqualTo("rows in owners change by +1");
        assertThat(Expectation.llmCallsAtMost(3).describe()).isEqualTo("at most 3 LLM call(s)");
        assertThat(Expectation.finishesWithin(Duration.ofSeconds(60)).describe())
                .isEqualTo("user has a reply within 60 s");
        assertThatThrownBy(() -> Expectation.llmCallsAtMost(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Expectation.finishesWithin(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ChaosScenario scenario(String id, Layer layer) {
        return new ChaosScenario() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Layer layer() {
                return layer;
            }

            @Override
            public String rationale() {
                return "test";
            }

            @Override
            public void arm(MockServerClient mock, RunMarker run) {}

            @Override
            public ExpectedBehavior expected() {
                return ExpectedBehavior.of("test", Expectation.noRawError());
            }
        };
    }
}
