package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Everything HTTP and JSON about talking to Ollama, kept out of the player.
 * <p>
 * Retries once when a response contains no tool call at all. Measured rate of
 * that happening on the tuned local profile is roughly 1 in 6 -- the model
 * answers in prose instead. Without the retry (and the caller's fallback) a
 * failed response is indistinguishable from a deliberate "do nothing".
 *
 * @author Darrell Best
 */
public class OllamaClient {

    private static final Logger logger = Logger.getLogger(OllamaClient.class);

    private final String baseUrl;
    private final String model;
    private int timeoutMs = 30_000;
    private int retryCount = 0;

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /**
     * @return the model's tool call, or null when it returned none even after a retry.
     */
    public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
        ToolCall first;
        try {
            first = attempt(prompt, tools);
        } catch (IOException e) {
            retryCount++;
            logger.info("Kanna: request failed (" + e.getMessage() + "), retrying once");
            return attempt(prompt, tools);
        }
        if (first != null) {
            return first;
        }
        retryCount++;
        logger.info("Kanna: no tool call in response, retrying once with an explicit instruction");
        return attempt(prompt + "\n\nYou MUST call the tool. Do not reply in prose.", tools);
    }

    private ToolCall attempt(String prompt, List<JsonObject> tools) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonArray toolArray = new JsonArray();
        for (JsonObject tool : tools) {
            toolArray.add(tool);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.add("tools", toolArray);
        body.addProperty("stream", false);

        String response = postJson(baseUrl + "/api/chat", body.toString());
        return parseResponse(response);
    }

    public static ToolCall parseResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject message = root.getAsJsonObject("message");
            if (message == null) {
                return null;
            }
            JsonArray calls = message.getAsJsonArray("tool_calls");
            if (calls == null || calls.size() == 0) {
                return null;
            }
            JsonObject function = calls.get(0).getAsJsonObject().getAsJsonObject("function");
            String name = function.get("name").getAsString();
            JsonElement rawArgs = function.get("arguments");
            JsonObject args;
            if (rawArgs != null && rawArgs.isJsonPrimitive()) {
                args = JsonParser.parseString(rawArgs.getAsString()).getAsJsonObject();
            } else if (rawArgs != null && rawArgs.isJsonObject()) {
                args = rawArgs.getAsJsonObject();
            } else {
                return null;
            }
            return new ToolCall(name, args);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonObject tool(String name, String description, JsonObject parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    public static JsonObject stringFieldSchema(String fieldName) {
        JsonObject properties = new JsonObject();
        properties.add(fieldName, typeObject("string"));
        JsonArray required = new JsonArray();
        required.add(fieldName);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    public static JsonObject pairArraySchema(String arrayName, String field1, String field2) {
        JsonObject itemProps = new JsonObject();
        itemProps.add(field1, typeObject("string"));
        itemProps.add(field2, typeObject("string"));
        JsonArray itemRequired = new JsonArray();
        itemRequired.add(field1);
        itemRequired.add(field2);
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.add("properties", itemProps);
        item.add("required", itemRequired);

        JsonObject array = new JsonObject();
        array.addProperty("type", "array");
        array.add("items", item);

        JsonObject properties = new JsonObject();
        properties.add(arrayName, array);
        JsonArray required = new JsonArray();
        required.add(arrayName);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private static JsonObject typeObject(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    private String postJson(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }

            int status = conn.getResponseCode();
            InputStream stream = status == 200 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } finally {
                reader.close();
            }
            if (status != 200) {
                throw new IOException("Ollama returned HTTP " + status + ": " + sb);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
