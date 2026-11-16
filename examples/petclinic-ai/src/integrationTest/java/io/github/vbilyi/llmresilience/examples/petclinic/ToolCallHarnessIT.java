package io.github.vbilyi.llmresilience.examples.petclinic;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Harness checks for tool calling (ADR-004): when the mocked model uses a tool <em>correctly</em>, the SUT runs it
 * and answers. If these fail, no TOOLS-layer chaos result can be trusted.
 *
 * <p>Arming comes from {@link PetClinicScenarios}; the assertions stay inline until the evaluator (Stage 7).
 */
class ToolCallHarnessIT {

    private static PetClinicAiEnvironment env;

    @BeforeAll
    static void startEnvironment() {
        env = PetClinicAiEnvironment.shared();
    }

    @BeforeEach
    void resetState() {
        env.resetState();
    }

    @Test
    void readOnlyToolRoundTrip() {
        RunMarker run = sendArmed(PetClinicScenarios.TOOL_LIST_OWNERS_OK, "Who are our owners?");

        assertThat(llmCalls(run)).isEqualTo(2); // tool call, then the answer
        assertDatabaseAtSeed();
    }

    @Test
    void vectorSearchToolCallsTheEmbeddingsEndpoint() {
        RunMarker run = sendArmed(PetClinicScenarios.TOOL_LIST_VETS_OK, "Do we have a vet called James?");

        assertThat(llmCalls(run)).isEqualTo(2);
        assertThat(env.mockServer().retrieveRecordedRequests(OpenAiRequests.embeddings()))
                .as("embeddings requests (spike Q8: a dimension probe may precede the query)")
                .isNotEmpty();
        assertDatabaseAtSeed();
    }

    @Test
    void writingToolAddsExactlyOneOwner() {
        RunMarker run = sendArmed(PetClinicScenarios.TOOL_ADD_OWNER_OK, "Please register Chaos Tester.");

        assertThat(llmCalls(run)).isEqualTo(2);
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS + 1);
        assertThat(env.database().count("pets")).isEqualTo(PetClinicDatabase.SEED_PETS);
    }

    /** Arms the scenario, sends one marked chat message and checks the user got the scenario's final answer. */
    private static RunMarker sendArmed(ChaosScenario scenario, String userMessage) {
        RunMarker run = RunMarker.random();
        scenario.arm(env.mockServer(), run);

        Response reply = given().baseUri(env.baseUrl())
                .contentType("application/json")
                .body("\"" + run.mark(userMessage) + "\"") // the UI sends a JSON string literal (spike Q4)
                .when().post("/chat");

        assertThat(reply.statusCode()).as("HTTP status; body: %s", reply.asString()).isEqualTo(200);
        assertThat(reply.asString()).isEqualTo(PetClinicScenarios.ANSWER_AFTER_TOOL);
        return run;
    }

    private static int llmCalls(RunMarker run) {
        return env.mockServer().retrieveRecordedRequests(run.chatCompletions()).length;
    }

    private static void assertDatabaseAtSeed() {
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS);
        assertThat(env.database().count("pets")).isEqualTo(PetClinicDatabase.SEED_PETS);
    }
}
