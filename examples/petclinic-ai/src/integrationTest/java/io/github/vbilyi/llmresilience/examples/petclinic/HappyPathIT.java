package io.github.vbilyi.llmresilience.examples.petclinic;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vbilyi.llmresilience.core.scenario.RunMarker;
import io.github.vbilyi.llmresilience.core.scenarios.HappyPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HAPPY_PATH. A harness test (ADR-004): the LLM behaves perfectly, so if the user does not get the mocked answer,
 * the environment or the mock wiring is broken and no chaos result can be trusted.
 *
 * <p>Arming comes from kit-core ({@link HappyPath}); the assertions stay inline until the evaluator (Stage 7).
 */
class HappyPathIT {

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
    void userReceivesTheMockedAnswer() {
        RunMarker run = RunMarker.random();
        new HappyPath().arm(env.mockServer(), run);

        Response reply = given().baseUri(env.baseUrl())
                .contentType("application/json")
                .body("\"" + run.mark("How many owners do we have?") + "\"") // a JSON string literal (spike Q4)
                .when().post("/chat");

        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(reply.asString()).isEqualTo(HappyPath.ANSWER);
        assertThat(env.mockServer().retrieveRecordedRequests(run.chatCompletions())).hasSize(1);
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS); // no side effects
    }
}
