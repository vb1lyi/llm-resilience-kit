package io.github.vbilyi.llmresilience.examples.petclinic;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.model.HttpRequest;

/**
 * HAPPY_PATH, written by hand (Stage 3). A harness test (ADR-004): the LLM behaves perfectly, so if the user does not
 * get the mocked answer, the environment or the mock wiring is broken and no chaos result can be trusted.
 */
class HappyPathIT {

    private static final String ANSWER = "We have 10 owners registered at the clinic.";

    // A complete, valid OpenAI chat completion (the shape captured in spike Q8).
    private static final String COMPLETION = """
            {
              "id": "chatcmpl-happy-path",
              "object": "chat.completion",
              "created": 1762300000,
              "model": "gpt-4o",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "%s"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 42, "completion_tokens": 9, "total_tokens": 51}
            }""".formatted(ANSWER);

    private static final HttpRequest CHAT_COMPLETIONS =
            request().withMethod("POST").withPath("/v1/chat/completions");

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
        env.mockServer()
                .when(CHAT_COMPLETIONS)
                .respond(response().withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(COMPLETION));

        Response reply = given().baseUri(env.baseUrl())
                .contentType("application/json")
                .body("\"How many owners do we have?\"") // the UI sends a JSON string literal (spike Q4)
                .when().post("/chat");

        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(reply.asString()).isEqualTo(ANSWER);
        assertThat(env.mockServer().retrieveRecordedRequests(CHAT_COMPLETIONS)).hasSize(1);
        assertThat(env.database().count("owners")).isEqualTo(PetClinicDatabase.SEED_OWNERS); // no side effects
    }
}
