package mage.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Startup check that Ollama is reachable and the configured model exists.
 * <p>
 * This is deliberately fail-fast. Kanna's combat methods catch Throwable and
 * fall back to declaring no attacks or blocks, so an unreachable Ollama would
 * silently produce a full run of games in which Kanna never fights -- a
 * meaningless win rate that still looks valid.
 *
 * @author Darrell Best
 */
public final class OllamaPreflight {

    private static final int TIMEOUT_MS = 5000;

    private OllamaPreflight() {
    }

    public static void check(String baseUrl, String model) {
        String body;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/tags").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            body = sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Ollama is not reachable at " + baseUrl
                    + " -- an LLM run cannot proceed, because Kanna would silently fall back to"
                    + " declaring no attacks and report a meaningless win rate. Cause: " + e);
        }
        if (!modelPresent(body, model)) {
            throw new IllegalStateException("Ollama is up at " + baseUrl + " but model '" + model
                    + "' is not installed. Run: ollama pull " + model);
        }
    }

    static boolean modelPresent(String tagsJson, String model) {
        try {
            JsonObject root = JsonParser.parseString(tagsJson).getAsJsonObject();
            JsonArray models = root.getAsJsonArray("models");
            if (models == null) {
                return false;
            }
            for (JsonElement element : models) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("name") && model.equals(entry.get("name").getAsString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
