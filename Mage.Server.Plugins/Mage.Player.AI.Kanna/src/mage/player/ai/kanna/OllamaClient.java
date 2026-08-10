package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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
 * failed response is indistinguishable from a deliberate "do nothing". A
 * request that fails outright with an {@link IOException} (unreachable host,
 * non-200 response, etc.) is also retried once, on the same one-retry budget,
 * before the exception is allowed to propagate to the caller.
 *
 * @author Darrell Best
 */
public class OllamaClient {

    private static final Logger logger = Logger.getLogger(OllamaClient.class);

    private final String baseUrl;
    private final String model;
    // DARRELLBEST-FORK: must stay comfortably above whatever num_predict can produce --
    // this was 30_000 while num_predict was 2048, which happened to hide the mismatch.
    // Raising num_predict to 8192 (see the Modelfiles) made generation legitimately run
    // longer -- a simple probe position took 20.9s, and a real mid-game board timed out
    // at 30s in live play. 120s gives headroom without the caller waiting forever on a
    // truly hung request.
    private int timeoutMs = 120_000;
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

        long start = System.nanoTime();
        String response = postJson(baseUrl + "/api/chat", body.toString());
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        logExchange(prompt, tools, response, latencyMs);
        return parseResponse(response);
    }

    // DARRELLBEST-FORK: this is the trace a human watches to understand WHY Kanna did
    // something, not just what it did -- the pre-pivot Kanna logged the prompt, the
    // model's `thinking` field, the raw tool-call arguments, and token usage, and the
    // agentic rewrite dropped all of it, leaving only outcome lines like "Kanna plays
    // X". Logged here rather than by extending ToolCall: this is the only place that
    // ever sees the raw response body, so message.thinking/prompt_eval_count/eval_count
    // never have to survive a trip through ToolCall just to be read once. Best-effort --
    // a malformed/unexpected response shape must never turn a logging failure into a
    // decision failure, so parse errors here are swallowed (with a warning) rather than
    // propagated.
    private void logExchange(String prompt, List<JsonObject> tools, String json, long latencyMs) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject message = root.has("message") && root.get("message").isJsonObject()
                    ? root.getAsJsonObject("message") : null;

            String thinking = null;
            String toolName = null;
            String toolArgs = null;
            if (message != null) {
                if (message.has("thinking") && message.get("thinking").isJsonPrimitive()) {
                    thinking = message.get("thinking").getAsString();
                }
                JsonArray calls = message.getAsJsonArray("tool_calls");
                if (calls != null && calls.size() > 0 && calls.get(0).isJsonObject()) {
                    JsonObject function = calls.get(0).getAsJsonObject().getAsJsonObject("function");
                    if (function != null) {
                        toolName = function.has("name") ? function.get("name").getAsString() : null;
                        toolArgs = function.has("arguments") ? function.get("arguments").toString() : null;
                    }
                }
            }
            String promptTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").getAsString() : "?";
            String completionTokens = root.has("eval_count") ? root.get("eval_count").getAsString() : "?";

            StringBuilder sb = new StringBuilder();
            sb.append("Kanna LLM call [").append(toolNames(tools)).append("] ")
                    .append(latencyMs).append("ms, ").append(promptTokens).append(" prompt / ")
                    .append(completionTokens).append(" completion tokens").append(System.lineSeparator());
            sb.append("  prompt: ").append(prompt).append(System.lineSeparator());
            if (thinking != null && !thinking.isEmpty()) {
                sb.append("  thinking: ").append(thinking).append(System.lineSeparator());
            }
            sb.append("  tool call: ").append(toolName == null ? "none" : toolName + " " + toolArgs);
            logger.info(sb.toString());
        } catch (Exception e) {
            logger.warn("Kanna: could not log LLM exchange detail - " + e);
        }
    }

    private static String toolNames(List<JsonObject> tools) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject tool : tools) {
            JsonObject function = tool.getAsJsonObject("function");
            String name = function != null && function.has("name") ? function.get("name").getAsString() : "?";
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
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
        return stringFieldSchema(new String[]{fieldName});
    }

    // DARRELLBEST-FORK: Strategist's commit_plan tool needs two required string fields
    // (goal, rationale), not one -- rather than a second, near-duplicate schema builder
    // living in Strategist itself, this widens the existing helper to any number of
    // fields. The single-arg overload above delegates here rather than duplicating the
    // body; Java resolves a one-argument call to the non-varargs overload first; so every
    // existing call site (stringFieldSchema("action_id"), etc.) is unaffected.
    public static JsonObject stringFieldSchema(String... fieldNames) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (String fieldName : fieldNames) {
            properties.add(fieldName, typeObject("string"));
            required.add(fieldName);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    // DARRELLBEST-FORK: commit_plan's schema needs two plain required string fields (goal,
    // rationale) plus two optional array-of-string fields (conditionals, prohibitions) on the
    // same object -- a shape neither stringFieldSchema (all-required-string) nor
    // pairArraySchema (array of two-field objects) covers. Kept generic here (caller supplies
    // which field names go in which group) rather than a Strategist-only one-off, same
    // "shared helper over a near-duplicate" reasoning the stringFieldSchema varargs widening
    // above already used. Array fields are deliberately NOT marked required: a turn with zero
    // live contingencies or zero prohibitions is a legitimate answer, not a malformed one.
    public static JsonObject stringAndArrayFieldSchema(String[] stringFields, String[] arrayFields) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (String fieldName : stringFields) {
            properties.add(fieldName, typeObject("string"));
            required.add(fieldName);
        }
        for (String fieldName : arrayFields) {
            JsonObject array = new JsonObject();
            array.addProperty("type", "array");
            array.add("items", typeObject("string"));
            properties.add(fieldName, array);
        }
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
            InputStream stream;
            if (status == 200) {
                stream = conn.getInputStream();
            } else {
                InputStream error = conn.getErrorStream();
                // getErrorStream() is null when the server sent no error body; without this
                // guard the reader below NPEs, which is unchecked and so escapes call()'s
                // IOException retry entirely.
                stream = error != null ? error : new ByteArrayInputStream(new byte[0]);
            }
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
