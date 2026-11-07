package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The environment refuses to hand anything but a dummy API key to the SUT. */
class DummyKeyGuardTest {

    @Test
    void acceptsTheDummyKey() {
        assertThatCode(() -> PetClinicAiEnvironment.requireDummyKey(PetClinicAiEnvironment.DUMMY_API_KEY))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsARealLookingKey() {
        assertThatThrownBy(() -> PetClinicAiEnvironment.requireDummyKey("sk-proj-AbC123xYz456"))
                .isInstanceOf(IllegalStateException.class);
    }
}
