package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.github.vbilyi.llmresilience.core.sut.ChatDriver;
import io.github.vbilyi.llmresilience.core.sut.ChatResult;
import io.github.vbilyi.llmresilience.junit5.LlmChaosSuite;
import io.github.vbilyi.llmresilience.junit5.LlmChaosTest;

/**
 * HAPPY_PATH. A harness test (ADR-004): the LLM behaves perfectly, so if the user does not get the mocked answer,
 * the environment or the mock wiring is broken and no chaos result can be trusted.
 *
 * <p>The extension resets the environment and arms the scenario; the assertions stay inline until the evaluator
 * (Stage 7).
 */
@LlmChaosSuite(environment = PetClinicAiEnvironment.class)
class HappyPathIT {

    @LlmChaosTest(scenarios = HappyPath.ID)
    void userReceivesTheMockedAnswer(ChatDriver chat, PetClinicAiEnvironment env) {
        ChatResult result = chat.send("How many owners do we have?");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).isEqualTo(HappyPath.ANSWER);
        assertThat(result.llmCalls()).isEqualTo(1);
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS); // no side effects
    }
}
