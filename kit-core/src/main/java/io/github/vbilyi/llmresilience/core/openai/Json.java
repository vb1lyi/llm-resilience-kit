package io.github.vbilyi.llmresilience.core.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Package-private Jackson helper shared by the builders. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize payload", e);
        }
    }
}
