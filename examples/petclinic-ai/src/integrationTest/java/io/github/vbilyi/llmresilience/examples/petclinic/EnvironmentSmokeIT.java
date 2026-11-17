package io.github.vbilyi.llmresilience.examples.petclinic;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;

import io.github.vbilyi.llmresilience.core.openai.OpenAiRequests;
import io.github.vbilyi.llmresilience.core.openai.OpenAiResponses;
import io.github.vbilyi.llmresilience.junit5.LlmChaosSuite;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Harness check (ADR-004): the whole environment comes up and every channel the tests rely on works.
 * If this fails, no chaos result is trustworthy.
 *
 * <p>Plain tests: no scenario is armed, the suite annotation only supplies the shared environment.
 */
@LlmChaosSuite(environment = PetClinicAiEnvironment.class)
class EnvironmentSmokeIT {

    @BeforeEach
    void resetState(PetClinicAiEnvironment env) {
        env.resetState();
    }

    @Test
    void homePageReturns200(PetClinicAiEnvironment env) {
        given().baseUri(env.baseUrl()).when().get("/").then().statusCode(200);
    }

    /**
     * Proves the SUT's LLM traffic goes to the mock. (Asking actuator for the base URL does not work: the SUT
     * masks property values as {@code ******}.) Only the wiring is checked here; the answer is HAPPY_PATH's job.
     */
    @Test
    void chatMessageReachesTheMockNotARealProvider(PetClinicAiEnvironment env) {
        env.mockServer().when(OpenAiRequests.chatCompletions()).respond(OpenAiResponses.text("pong").toResponse());

        assertThat(env.chat("smoke test", Duration.ofSeconds(30)).status()).isEqualTo(200);

        assertThat(env.mockServer().retrieveRecordedRequests(OpenAiRequests.chatCompletions())).hasSize(1);
    }

    @Test
    void mockServerIsReachableAndStartsEmpty(PetClinicAiEnvironment env) {
        assertThat(env.mockServer().hasStarted()).isTrue();
        assertThat(env.mockServer().retrieveRecordedRequests(request())).isEmpty();
    }

    @Test
    void databaseIsReachableAndAtSeed(PetClinicAiEnvironment env) {
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS);
        assertThat(env.database().count("pets")).isEqualTo(PetClinicDatabase.SEED_PETS);
        assertThat(env.database().count("visits")).isEqualTo(PetClinicDatabase.SEED_VISITS);
    }

    @Test
    void resetStateRemovesRowsAddedByATest(PetClinicAiEnvironment env) throws Exception {
        try (Connection c = env.database().openConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO owners (first_name, last_name, address, city, telephone)"
                    + " VALUES ('Smoke', 'Test', '1 Test St.', 'Testville', '0123456789')");
        }
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS + 1);

        env.resetState();

        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS);
    }
}
