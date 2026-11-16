package io.github.vbilyi.llmresilience.core.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInScenariosTest {

    private final List<ChaosScenario> all = BuiltInScenarios.catalog().all();

    @Test
    void happyPathComesFirstAndIsTheOnlyControlScenario() {
        assertThat(all.getFirst().id()).isEqualTo(HappyPath.ID);
        assertThat(BuiltInScenarios.catalog().byLayer(Layer.CONTROL)).extracting(ChaosScenario::id)
                .containsExactly(HappyPath.ID);
    }

    @Test
    void coversEveryLayer() {
        assertThat(all).hasSizeGreaterThanOrEqualTo(8);
        assertThat(all).extracting(ChaosScenario::layer).contains(Layer.values());
    }

    @Test
    void slowScenariosAreOnlyInTheNightlyCatalog() {
        assertThat(all).extracting(ChaosScenario::id).doesNotContain(ServerError503Persistent.ID);
        assertThat(BuiltInScenarios.nightly().all()).extracting(ChaosScenario::id)
                .containsExactly(ServerError503Persistent.ID);
        assertThat(all).allSatisfy(s -> assertThat(s.timeBudget()).as(s.id()).isLessThanOrEqualTo(Duration.ofMinutes(2)));
    }

    @Test
    void everyScenarioIsDocumentedAndHasExpectations() {
        assertThat(all).allSatisfy(s -> {
            assertThat(s.rationale()).as(s.id() + " rationale").isNotBlank();
            assertThat(s.expected().description()).as(s.id() + " description").isNotBlank();
            assertThat(s.expected().expectations()).as(s.id() + " expectations").isNotEmpty();
        });
    }

    @Test
    void rateLimitScenariosExpectRetryAfterToBeHonored() {
        assertThat(new RateLimit429().expected().expectations())
                .containsExactly(Expectation.noRawError(), Expectation.honorsRetryAfter(RateLimit429.RETRY_AFTER_SECONDS));
        assertThat(new RateLimit429Transient().expected().expectations())
                .contains(Expectation.userSees(HappyPath.ANSWER));
    }
}
