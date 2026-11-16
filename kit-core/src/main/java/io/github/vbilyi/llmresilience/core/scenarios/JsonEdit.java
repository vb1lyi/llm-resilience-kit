package io.github.vbilyi.llmresilience.core.scenarios;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;

/** Package-private helper: changes a valid payload from the builders into one the builders refuse to produce. */
final class JsonEdit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonEdit() {}

    static String edit(String json, Consumer<ObjectNode> change) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);
            change.accept(root);
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot edit payload", e);
        }
    }
}
