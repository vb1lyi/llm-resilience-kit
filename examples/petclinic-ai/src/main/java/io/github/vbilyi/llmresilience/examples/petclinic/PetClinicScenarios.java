package io.github.vbilyi.llmresilience.examples.petclinic;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.ExpectedBehavior;
import io.github.vbilyi.llmresilience.core.scenario.Expectation;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;
import io.github.vbilyi.llmresilience.core.scenarios.BuiltInScenarios;
import java.util.function.BiConsumer;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;

/**
 * The scenarios that need PetClinic AI's own tool names and tables (spike Q5), and the full catalog for this SUT.
 *
 * <p>Tool turns are told apart by order (ADR-007): the first chat completion of the run gets the tool call, the
 * follow-up turn gets the final answer.
 */
public final class PetClinicScenarios {

    /** What the mocked model says after a tool has run. */
    public static final String ANSWER_AFTER_TOOL = "Done - this is the mocked model's answer after the tool call.";

    /** How many times in a row the model asks for a tool in {@code TOOL_CALL_LOOP}. Bounded: see spike risk R11. */
    public static final int LOOP_TOOL_CALLS = 25;

    /** The most LLM calls a single user message should ever cause. */
    public static final int LOOP_CALL_BUDGET = 10;

    // ---- control scenarios: a well-behaved model uses each kind of tool. Harness checks (ADR-004). ----

    /** {@code TOOL_LIST_OWNERS_OK}: a read-only tool round trip. */
    public static final ChaosScenario TOOL_LIST_OWNERS_OK = new Scenario(
            "TOOL_LIST_OWNERS_OK", Layer.CONTROL,
            "Baseline for tool calling: the model asks for listOwners, gets the result, and answers.",
            ExpectedBehavior.of("The user gets the answer after two LLM calls; nothing is written.",
                    Expectation.userSees(ANSWER_AFTER_TOOL), Expectation.llmCallsAtMost(2), Expectation.noDbChanges()),
            (mock, run) -> toolCallThenAnswer(mock, run, "listOwners", "{}"));

    /** {@code TOOL_LIST_VETS_OK}: the tool runs a vector search, so the SUT also calls the embeddings endpoint. */
    public static final ChaosScenario TOOL_LIST_VETS_OK = new Scenario(
            "TOOL_LIST_VETS_OK", Layer.CONTROL,
            "Baseline for the embeddings path: listVets runs a vector search, which calls /v1/embeddings.",
            ExpectedBehavior.of("The user gets the answer after two LLM calls; nothing is written.",
                    Expectation.userSees(ANSWER_AFTER_TOOL), Expectation.llmCallsAtMost(2), Expectation.noDbChanges()),
            (mock, run) -> {
                toolCallThenAnswer(mock, run, "listVets", "{\"vet\": {\"firstName\": \"James\"}}");
                mock.when(OpenAiRequests.embeddings())
                        .respond(OpenAiResponses.embeddings(OpenAiResponses.ADA_002_DIMENSIONS).toResponse());
            });

    /** {@code TOOL_ADD_OWNER_OK}: a tool that writes one row. */
    public static final ChaosScenario TOOL_ADD_OWNER_OK = new Scenario(
            "TOOL_ADD_OWNER_OK", Layer.CONTROL,
            "Baseline for side effects: a valid addOwnerToPetclinic call inserts exactly one owner.",
            ExpectedBehavior.of("The user gets the answer and one owner is added.",
                    Expectation.userSees(ANSWER_AFTER_TOOL), Expectation.llmCallsAtMost(2),
                    Expectation.dbRowDelta("owners", 1), Expectation.dbRowDelta("pets", 0)),
            (mock, run) -> toolCallThenAnswer(mock, run, "addOwnerToPetclinic", """
                    {"owner": {"firstName": "Chaos", "lastName": "Tester", "address": "1 Mock Street",
                               "city": "Testville", "telephone": "0123456789"}}"""));

    // ---- chaos scenarios ----

    /** {@code TOOL_ARGS_INVALID_TYPES}: {@code ownerId} is the word "seven" ("7" would be coerced - spike Q11). */
    public static final ChaosScenario TOOL_ARGS_INVALID_TYPES = new Scenario(
            "TOOL_ARGS_INVALID_TYPES", Layer.TOOLS,
            "Models fill tool arguments with the wrong types - a word where the schema says integer - "
                    + "especially after a schema change or with smaller models. The API does not validate arguments.",
            ExpectedBehavior.of("The SUT rejects the call without crashing and without writing anything.",
                    Expectation.noRawError(), Expectation.llmCallsAtMost(2), Expectation.noDbChanges()),
            (mock, run) -> toolCallThenAnswer(mock, run, "addPetToOwner",
                    "{\"pet\": {\"name\": \"Rex\"}, \"ownerId\": \"seven\"}"));

    /** {@code TOOL_CALL_LOOP}: the model keeps asking for the same tool, {@value #LOOP_TOOL_CALLS} times. */
    public static final ChaosScenario TOOL_CALL_LOOP = new Scenario(
            "TOOL_CALL_LOOP", Layer.TOOLS,
            "A model can get stuck asking for the same tool again and again. Every round is a paid LLM call "
                    + "with a longer prompt; without a cap, one user message can cost hundreds of calls.",
            ExpectedBehavior.of("The SUT stops the loop after at most " + LOOP_CALL_BUDGET
                            + " LLM calls and tells the user something readable.",
                    Expectation.llmCallsAtMost(LOOP_CALL_BUDGET), Expectation.noRawError(), Expectation.noDbChanges()),
            (mock, run) -> {
                mock.when(run.chatCompletions(), Times.exactly(LOOP_TOOL_CALLS))
                        .respond(OpenAiResponses.toolCall("listOwners", "{}").toResponse());
                mock.when(run.chatCompletions()).respond(OpenAiResponses.text(ANSWER_AFTER_TOOL).toResponse());
            });

    private PetClinicScenarios() {}

    /**
     * Everything that can be run against PetClinic AI on every build: the kit's built-in scenarios plus the ones
     * above.
     *
     * @return the catalog
     */
    public static ScenarioCatalog catalog() {
        return BuiltInScenarios.catalog().plus(
                TOOL_LIST_OWNERS_OK, TOOL_LIST_VETS_OK, TOOL_ADD_OWNER_OK, TOOL_ARGS_INVALID_TYPES, TOOL_CALL_LOOP);
    }

    private static void toolCallThenAnswer(MockServerClient mock, RunMarker run, String tool, String argumentsJson) {
        mock.when(run.chatCompletions(), Times.exactly(1))
                .respond(OpenAiResponses.toolCall(tool, argumentsJson).toResponse());
        mock.when(run.chatCompletions()).respond(OpenAiResponses.text(ANSWER_AFTER_TOOL).toResponse());
    }

    /** A scenario given as data plus its arming code. */
    private record Scenario(String id, Layer layer, String rationale, ExpectedBehavior expected,
            BiConsumer<MockServerClient, RunMarker> arming) implements ChaosScenario {

        @Override
        public void arm(MockServerClient mock, RunMarker run) {
            arming.accept(mock, run);
        }
    }
}
