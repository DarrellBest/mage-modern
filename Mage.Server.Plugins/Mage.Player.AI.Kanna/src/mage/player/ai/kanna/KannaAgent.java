package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The decision loop. The model may call read-only inspection tools to look
 * deeper, then must commit exactly once. A hard cap stops a decision spiralling.
 * <p>
 * When the model fails -- no tool call, an id that does not exist, transport
 * error, or the cap -- the agent returns Decision.fallback() and the caller's
 * heuristics decide. It never returns "do nothing" as a way of failing, because
 * that is indistinguishable from deciding to do nothing.
 *
 * @author Darrell Best
 */
public final class KannaAgent {

    private static final Logger logger = Logger.getLogger(KannaAgent.class);

    /** Answers a read-only inspection call, or returns null if this is not one. */
    public interface InspectionAnswerer {
        String answer(ToolCall call);
    }

    /** Decides whether a committed (a, b) pair is legal in the real game. */
    public interface PairValidator {
        boolean isValid(String first, String second);
    }

    private final OllamaClient client;
    private final int maxToolCalls;
    private int invalidCount = 0;

    public KannaAgent(OllamaClient client, int maxToolCalls) {
        this.client = client;
        this.maxToolCalls = maxToolCalls;
    }

    /**
     * @return how many times the model gave a genuinely bad answer (unresolvable id,
     * unknown tool, malformed pair, missing arguments). Deliberately excludes the
     * transport-exception path and the cap-reached path: those are infrastructure
     * failure and budget exhaustion, not the model answering badly, and this count
     * is meant to measure model quality specifically -- do not fold those in.
     */
    public int getInvalidCount() {
        return invalidCount;
    }

    public Decision chooseAction(String prompt, ActionCatalog catalog, InspectionAnswerer answerer) {
        List<JsonObject> tools = new ArrayList<JsonObject>();
        tools.add(OllamaClient.tool("choose_action",
                "Commit to exactly one action, by its short id.",
                OllamaClient.stringFieldSchema("action_id")));
        tools.add(OllamaClient.tool("get_card_text",
                "Read the full text of a card by its short id.",
                OllamaClient.stringFieldSchema("id")));
        tools.add(OllamaClient.tool("show_all_actions",
                "List every legal action, not just the shortlist.",
                OllamaClient.stringFieldSchema("unused")));

        StringBuilder conversation = new StringBuilder(prompt);
        for (int i = 0; i < maxToolCalls; i++) {
            ToolCall call;
            try {
                call = client.call(conversation.toString(), tools);
            } catch (Exception e) {
                logger.warn("Kanna: LLM transport failure, deferring to heuristics - " + e);
                return Decision.fallback();
            }
            if (call == null) {
                logger.warn("Kanna: no tool call returned, deferring to heuristics");
                invalidCount++;
                return Decision.fallback();
            }
            if ("choose_action".equals(call.name)) {
                String id = optString(call.arguments, "action_id");
                if (catalog.resolve(id) == null) {
                    logger.warn("Kanna: model chose unknown action id '" + id + "', deferring to heuristics");
                    invalidCount++;
                    return Decision.fallback();
                }
                return Decision.of(id);
            }
            String answer = answerer.answer(call);
            if (answer == null) {
                logger.warn("Kanna: unknown tool '" + call.name + "', deferring to heuristics");
                invalidCount++;
                return Decision.fallback();
            }
            conversation.append(System.lineSeparator())
                    .append("Result of ").append(call.name).append(": ").append(answer);
        }
        logger.warn("Kanna: hit the " + maxToolCalls + "-call cap without committing, deferring to heuristics");
        return Decision.fallback();
    }

    public Decision choosePairs(String prompt, String toolName, String arrayField,
                                String field1, String field2, PairValidator validator) {
        List<JsonObject> tools = new ArrayList<JsonObject>();
        tools.add(OllamaClient.tool(toolName,
                "Commit to the chosen assignments, using only the short ids listed.",
                OllamaClient.pairArraySchema(arrayField, field1, field2)));

        ToolCall call;
        try {
            call = client.call(prompt, tools);
        } catch (Exception e) {
            logger.warn("Kanna: LLM transport failure, deferring to heuristics - " + e);
            return Decision.fallback();
        }
        if (call == null) {
            logger.warn("Kanna: no tool call returned for " + toolName + ", deferring to heuristics");
            invalidCount++;
            return Decision.fallback();
        }
        if (call.arguments == null) {
            logger.warn("Kanna: tool call carried no arguments, deferring to heuristics");
            invalidCount++;
            return Decision.fallback();
        }
        JsonArray array = call.arguments.getAsJsonArray(arrayField);
        if (array == null) {
            logger.warn("Kanna: tool call had no '" + arrayField + "' array, deferring to heuristics");
            invalidCount++;
            return Decision.fallback();
        }
        List<String[]> pairs = new ArrayList<String[]>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                invalidCount++;
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String a = optString(object, field1);
            String b = optString(object, field2);
            if (a == null || b == null || !validator.isValid(a, b)) {
                logger.warn("Kanna: dropping invalid pair " + a + " -> " + b);
                invalidCount++;
                continue;
            }
            pairs.add(new String[]{a, b});
        }
        return Decision.ofPairs(pairs);
    }

    private static String optString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }
}
