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

    @Test
    public void nonSuccessResponseWithNoErrorBodySurfacesAsIOException() throws Exception {
        // HttpURLConnection#getErrorStream() is documented to return null when the server
        // sent a non-2xx status with no error body -- an empty-body 500 is the realistic way
        // that happens. What matters is that the failure arrives as IOException (catchable,
        // retried) and never as a NullPointerException from wrapping a null stream in a reader.
        //
        // A bare accept()-then-close() (no bytes at all) does NOT reliably exercise this: it
        // typically fails earlier, inside getResponseCode() itself (e.g. SocketTimeoutException
        // or "unexpected end of stream"), before the code under test ever reaches the
        // getErrorStream() branch -- so it would pass identically whether or not the null-stream
        // guard exists. To pin the actual fix, the fake server below writes a real minimal HTTP
        // response (status line + Content-Length: 0) and then closes, which reliably produces
        // status=500 with a null error stream. It loops so both the initial attempt and the
        // one retry get served, keeping the test deterministic and fast (no read-timeout waits).
        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!server.isClosed()) {
                        java.net.Socket socket = server.accept();
                        try {
                            java.io.OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 500 Internal Server Error\r\n"
                                    + "Content-Length: 0\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.flush();
                        } finally {
                            socket.close();
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed under us at test teardown; not interesting
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            OllamaClient client = new OllamaClient("http://127.0.0.1:" + server.getLocalPort(), "any-model");
            client.setTimeoutMs(1000);
            try {
                client.call("prompt", new java.util.ArrayList<JsonObject>());
                org.junit.Assert.fail("expected IOException");
            } catch (java.io.IOException expected) {
                assertTrue(client.getRetryCount() >= 1);
            } catch (RuntimeException unexpected) {
                org.junit.Assert.fail("must surface as IOException, not " + unexpected);
            }
        } finally {
            server.close();
        }
    }
}
