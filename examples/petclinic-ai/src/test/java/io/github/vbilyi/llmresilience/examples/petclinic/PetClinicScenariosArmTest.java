package io.github.vbilyi.llmresilience.examples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;

/**
 * Each PetClinic scenario's {@code arm()} makes a real MockServer behave as documented. The server runs inside this
 * JVM (no Docker, no SUT); the test plays the SUT's part.
 */
class PetClinicScenariosArmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<ChaosScenario> OWN = List.of(
            PetClinicScenarios.TOOL_LIST_OWNERS_OK, PetClinicScenarios.TOOL_LIST_VETS_OK,
            PetClinicScenarios.TOOL_ADD_OWNER_OK, PetClinicScenarios.TOOL_ARGS_INVALID_TYPES,
            PetClinicScenarios.TOOL_CALL_LOOP);

    private static ClientAndServer mock;
    private final HttpClient http = HttpClient.newHttpClient();
    private final RunMarker run = RunMarker.random();

    @BeforeAll
    static void startMockServer() {
        mock = ClientAndServer.startClientAndServer(0);
    }

    @AfterAll
    static void stopMockServer() {
        mock.stop();
    }

    @BeforeEach
    void resetMockServer() {
        mock.reset();
    }

    @Test
    void catalogIsTheBuiltInScenariosPlusThePetClinicOnes() {
        assertThat(PetClinicScenarios.catalog().all()).containsAll(OWN);
        assertThat(PetClinicScenarios.catalog().get("HAPPY_PATH").layer()).isEqualTo(Layer.CONTROL);
        assertThat(PetClinicScenarios.catalog().byLayer(Layer.TOOLS)).extracting(ChaosScenario::id)
                .containsExactly("TOOL_UNKNOWN_NAME", "TOOL_ARGS_INVALID_TYPES", "TOOL_CALL_LOOP");
    }

    @Test
    void listOwnersAsksForTheToolOnceThenAnswers() throws Exception {
        PetClinicScenarios.TOOL_LIST_OWNERS_OK.arm(mock, run);

        assertToolCall(chat(run), "listOwners");
        assertAnswer(chat(run));
    }

    @Test
    void listVetsAlsoAnswersEmbeddings() throws Exception {
        PetClinicScenarios.TOOL_LIST_VETS_OK.arm(mock, run);

        JsonNode arguments = MAPPER.readTree(assertToolCall(chat(run), "listVets"));
        assertThat(arguments.at("/vet/firstName").asText()).isEqualTo("James");
        assertAnswer(chat(run));

        HttpResponse<String> embeddings = post(OpenAiRequests.EMBEDDINGS_PATH,
                "{\"input\": [\"Hello World\"], \"model\": \"text-embedding-ada-002\"}");
        assertThat(embeddings.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(embeddings.body()).at("/data/0/embedding")).hasSize(1536);
    }

    @Test
    void addOwnerSendsACompleteValidOwner() throws Exception {
        PetClinicScenarios.TOOL_ADD_OWNER_OK.arm(mock, run);

        JsonNode owner = MAPPER.readTree(assertToolCall(chat(run), "addOwnerToPetclinic")).path("owner");
        assertThat(owner.path("firstName").asText()).isNotBlank();
        assertThat(owner.path("lastName").asText()).isNotBlank();
        assertThat(owner.path("address").asText()).isNotBlank();
        assertThat(owner.path("city").asText()).isNotBlank();
        assertThat(owner.path("telephone").asText()).matches("\\d{10}"); // the SUT's own validation rule
        assertAnswer(chat(run));
    }

    @Test
    void invalidTypesSendsAWordWhereTheOwnerIdShouldBe() throws Exception {
        PetClinicScenarios.TOOL_ARGS_INVALID_TYPES.arm(mock, run);

        JsonNode arguments = MAPPER.readTree(assertToolCall(chat(run), "addPetToOwner"));
        assertThat(arguments.path("ownerId").isTextual()).isTrue();
        assertThat(arguments.path("ownerId").asText()).isEqualTo("seven");
        assertAnswer(chat(run));
    }

    @Test
    void toolCallLoopIsBoundedAndLongerThanTheBudgetItExpects() throws Exception {
        PetClinicScenarios.TOOL_CALL_LOOP.arm(mock, run);

        for (int call = 1; call <= PetClinicScenarios.LOOP_TOOL_CALLS; call++) {
            assertToolCall(chat(run), "listOwners");
        }
        assertAnswer(chat(run)); // the loop ends: never arm an unbounded one (spike R11)
        assertThat(PetClinicScenarios.LOOP_TOOL_CALLS).isGreaterThan(PetClinicScenarios.LOOP_CALL_BUDGET);
    }

    /** ADR-007: late chat completions of an earlier run get MockServer's 404, whatever is armed. */
    @Test
    void noScenarioAnswersChatCompletionsOfAnotherRun() throws Exception {
        RunMarker earlierRun = RunMarker.random();

        for (ChaosScenario scenario : OWN) {
            mock.reset();
            scenario.arm(mock, run);

            assertThat(chat(earlierRun).statusCode()).as(scenario.id()).isEqualTo(404);
        }
    }

    /** Returns the tool call's arguments (a JSON document as a string). */
    private static String assertToolCall(HttpResponse<String> response, String tool) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode choice = MAPPER.readTree(response.body()).at("/choices/0");
        assertThat(choice.path("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(choice.at("/message/tool_calls/0/function/name").asText()).isEqualTo(tool);
        return choice.at("/message/tool_calls/0/function/arguments").asText();
    }

    private static void assertAnswer(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).at("/choices/0/message/content").asText())
                .isEqualTo(PetClinicScenarios.ANSWER_AFTER_TOOL);
    }

    private HttpResponse<String> chat(RunMarker marker) throws Exception {
        String body = """
                {"model": "gpt-4o", "stream": false,
                 "messages": [{"role": "system", "content": "You are a friendly AI assistant..."},
                              {"role": "user", "content": "\\"%s\\""}]}""".formatted(marker.mark("Hello"));
        return post(OpenAiRequests.CHAT_COMPLETIONS_PATH, body);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + mock.getLocalPort() + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();
        return http.send(request, BodyHandlers.ofString());
    }
}
