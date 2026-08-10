package org.mage.test.kanna;

import com.google.gson.JsonObject;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OllamaClientTest {

    private static final String WITH_TOOL_CALL =
            "{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{"
                    + "\"name\":\"choose_action\",\"arguments\":{\"action_id\":\"act-3\"}}}]},"
                    + "\"prompt_eval_count\":523,\"eval_count\":88}";

    private static final String PROSE_NO_TOOL_CALL =
            "{\"message\":{\"role\":\"assistant\",\"content\":"
                    + "\"Looking at the board state, I need to maximize damage.\"},"
                    + "\"prompt_eval_count\":523,\"eval_count\":40}";

    @Test
    public void parsesAToolCallAndItsArguments() {
        ToolCall call = OllamaClient.parseResponse(WITH_TOOL_CALL);
        assertNotNull(call);
        assertEquals("choose_action", call.name);
        assertEquals("act-3", call.arguments.get("action_id").getAsString());
    }

    @Test
    public void proseResponseWithNoToolCallParsesToNull() {
        assertNull(OllamaClient.parseResponse(PROSE_NO_TOOL_CALL));
    }

    @Test
    public void emptyToolCallArrayParsesToNull() {
        assertNull(OllamaClient.parseResponse("{\"message\":{\"tool_calls\":[]}}"));
    }

    @Test
    public void malformedJsonParsesToNullRatherThanThrowing() {
        assertNull(OllamaClient.parseResponse("not json at all"));
        assertNull(OllamaClient.parseResponse(""));
        assertNull(OllamaClient.parseResponse("{}"));
    }

    @Test
    public void argumentsDeliveredAsAJsonStringAreStillParsed() {
        // some models return arguments as a JSON-encoded string rather than an object
        String body = "{\"message\":{\"tool_calls\":[{\"function\":{"
                + "\"name\":\"choose_action\",\"arguments\":\"{\\\"action_id\\\":\\\"act-7\\\"}\"}}]}}";
        ToolCall call = OllamaClient.parseResponse(body);
        assertNotNull(call);
        assertEquals("act-7", call.arguments.get("action_id").getAsString());
    }

    @Test
    public void toolSchemaHasTheShapeOllamaExpects() {
        JsonObject params = OllamaClient.stringFieldSchema("action_id");
        JsonObject tool = OllamaClient.tool("choose_action", "Choose one action.", params);
        assertEquals("function", tool.get("type").getAsString());
        JsonObject fn = tool.getAsJsonObject("function");
        assertEquals("choose_action", fn.get("name").getAsString());
        assertEquals("Choose one action.", fn.get("description").getAsString());
        assertNotNull(fn.getAsJsonObject("parameters"));
    }

    @Test
    public void stringFieldSchemaMarksTheFieldRequired() {
        JsonObject schema = OllamaClient.stringFieldSchema("action_id");
        assertEquals("object", schema.get("type").getAsString());
        assertNotNull(schema.getAsJsonObject("properties").getAsJsonObject("action_id"));
        assertTrue(schema.getAsJsonArray("required").toString().contains("action_id"));
    }

    @Test
    public void pairArraySchemaDescribesAnArrayOfTwoFieldObjects() {
        JsonObject schema = OllamaClient.pairArraySchema("attacks", "attacker_id", "defender_id");
        JsonObject attacks = schema.getAsJsonObject("properties").getAsJsonObject("attacks");
        assertEquals("array", attacks.get("type").getAsString());
        JsonObject item = attacks.getAsJsonObject("items");
        assertEquals("object", item.get("type").getAsString());
        assertNotNull(item.getAsJsonObject("properties").getAsJsonObject("attacker_id"));
        assertNotNull(item.getAsJsonObject("properties").getAsJsonObject("defender_id"));
        assertTrue(item.getAsJsonArray("required").toString().contains("defender_id"));
    }

    @Test
    public void unreachableHostSurfacesAsIOExceptionNotSilence() {
        OllamaClient client = new OllamaClient("http://127.0.0.1:1", "any-model");
        client.setTimeoutMs(300);
        try {
            client.call("prompt", new java.util.ArrayList<JsonObject>());
            org.junit.Assert.fail("expected IOException");
        } catch (java.io.IOException expected) {
            assertTrue(client.getRetryCount() >= 1);
        }
    }
}
