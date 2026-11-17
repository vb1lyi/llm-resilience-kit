package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.sut.ChatDriver;
import io.github.vbilyi.llmresilience.core.sut.ChatResult;
import io.github.vbilyi.llmresilience.junit5.LlmChaosSuite;
import io.github.vbilyi.llmresilience.junit5.LlmChaosTest;

/**
 * Harness checks for tool calling (ADR-004): when the mocked model uses a tool <em>correctly</em>, the SUT runs it
 * and answers. If these fail, no TOOLS-layer chaos result can be trusted.
 *
 * <p>Arming comes from {@link PetClinicScenarios}; the assertions stay inline until the evaluator (Stage 7).
 */
@LlmChaosSuite(environment = PetClinicAiEnvironment.class)
class ToolCallHarnessIT {

    @LlmChaosTest(scenarios = "TOOL_LIST_OWNERS_OK")
    void readOnlyToolRoundTrip(ChatDriver chat, PetClinicAiEnvironment env) {
        ChatResult result = chat.send("Who are our owners?");

        assertAnsweredAfterOneToolRound(result);
        assertDatabaseAtSeed(env);
    }

    @LlmChaosTest(scenarios = "TOOL_LIST_VETS_OK")
    void vectorSearchToolCallsTheEmbeddingsEndpoint(ChatDriver chat, PetClinicAiEnvironment env) {
        ChatResult result = chat.send("Do we have a vet called James?");

        assertAnsweredAfterOneToolRound(result);
        assertThat(env.mockServer().retrieveRecordedRequests(OpenAiRequests.embeddings()))
                .as("embeddings requests (spike Q8: a dimension probe may precede the query)")
                .isNotEmpty();
        assertDatabaseAtSeed(env);
    }

    @LlmChaosTest(scenarios = "TOOL_ADD_OWNER_OK")
    void writingToolAddsExactlyOneOwner(ChatDriver chat, PetClinicAiEnvironment env) {
        ChatResult result = chat.send("Please register Chaos Tester.");

        assertAnsweredAfterOneToolRound(result);
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS + 1);
        assertThat(env.database().count("pets")).isEqualTo(PetClinicDatabase.SEED_PETS);
    }

    private static void assertAnsweredAfterOneToolRound(ChatResult result) {
        assertThat(result.status()).as("HTTP status; body: %s", result.body()).isEqualTo(200);
        assertThat(result.body()).isEqualTo(PetClinicScenarios.ANSWER_AFTER_TOOL);
        assertThat(result.llmCalls()).isEqualTo(2); // tool call, then the answer
    }

    private static void assertDatabaseAtSeed(PetClinicAiEnvironment env) {
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS);
        assertThat(env.database().count("pets")).isEqualTo(PetClinicDatabase.SEED_PETS);
    }
}
