package io.github.vbilyi.llmresilience.core.openai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpResponse;

/**
 * Builds an OpenAI embeddings response ({@code POST /v1/embeddings}) with deterministic vectors.
 *
 * <p>Every component is {@code 1/sqrt(dimensions)}, so each vector has unit length and cosine similarity is well
 * defined. A SUT that runs a vector search on these vectors gets a valid (if meaningless) ranking.
 */
public final class EmbeddingsBuilder {

    private final int dimensions;
    private int count = 1;
    private String model = "text-embedding-ada-002";

    EmbeddingsBuilder(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be >= 1: " + dimensions);
        }
        this.dimensions = dimensions;
    }

    /**
     * Sets how many vectors to return (one per input string of the request).
     *
     * @param count number of vectors
     * @return this builder
     */
    public EmbeddingsBuilder count(int count) {
        this.count = count;
        return this;
    }

    /**
     * Sets the model name echoed in the response.
     *
     * @param model e.g. {@code text-embedding-ada-002}
     * @return this builder
     */
    public EmbeddingsBuilder model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Builds the JSON body.
     *
     * @return the response body as a JSON string
     */
    public String json() {
        double component = 1.0 / Math.sqrt(dimensions);
        ObjectNode root = Json.object().put("object", "list");
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < count; i++) {
            ArrayNode vector = data.addObject().put("object", "embedding").put("index", i).putArray("embedding");
            for (int d = 0; d < dimensions; d++) {
                vector.add(component);
            }
        }
        root.put("model", model);
        root.putObject("usage").put("prompt_tokens", 2).put("total_tokens", 2);
        return Json.write(root);
    }

    /**
     * Builds an HTTP 200 MockServer response with this body.
     *
     * @return the response, ready for {@code mock.when(...).respond(...)}
     */
    public HttpResponse toResponse() {
        return OpenAiResponses.jsonResponse(200, json());
    }
}
