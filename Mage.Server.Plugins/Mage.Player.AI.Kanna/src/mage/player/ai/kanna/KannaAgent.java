package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // DARRELLBEST-FORK: names, not the JsonObject schemas themselves -- see
    // INSPECTION_TOOLS below for why the schemas stay centralised here rather than
    // being duplicated at every InspectionAnswerer implementation.
    public static final String TOOL_GET_CARD_TEXT = "get_card_text";
    public static final String TOOL_SHOW_ALL_ACTIONS = "show_all_actions";

    // DARRELLBEST-FORK: the root cause of the "advertised but unhandled tool" bug was
    // that chooseAction() used to hardcode the full tool list while each call site
    // supplied its own InspectionAnswerer with no way to say which of those tools it
    // actually handled -- ComputerPlayerKanna.chooseTarget()'s answerer only ever
    // handled show_all_actions, so a model calling the also-advertised get_card_text
    // got a null answer, which chooseAction() below treats as "unknown tool": an
    // immediate fallback() plus an invalidCount++ for a tool the agent itself told the
    // model existed. Fixing that one call site is not enough -- nothing stopped the
    // same drift from happening again at the next answerer. So the contract is now:
    // InspectionAnswerer.supportedTools() declares which of the *known* inspection
    // tools (the keys here) it answers, and chooseAction() advertises exactly that
    // subset, in this canonical order, rather than a fixed list. A null from
    // answerer.answer() therefore can only happen for a tool name the model invented
    // that isn't even in this map -- a genuine model error, which is what invalidCount
    // is meant to measure. Schemas live here, once, rather than at each answerer,
    // because the wire shape (parameter name, type) is a property of the tool itself,
    // not of who happens to be answering it this call.
    private static final Map<String, JsonObject> INSPECTION_TOOLS = buildInspectionTools();

    private static Map<String, JsonObject> buildInspectionTools() {
        Map<String, JsonObject> tools = new LinkedHashMap<String, JsonObject>();
        tools.put(TOOL_GET_CARD_TEXT, OllamaClient.tool(TOOL_GET_CARD_TEXT,
                "Read the full oracle text and current state (e.g. counters) of a card by its short id.",
                OllamaClient.stringFieldSchema("id")));
        tools.put(TOOL_SHOW_ALL_ACTIONS, OllamaClient.tool(TOOL_SHOW_ALL_ACTIONS,
                "List every legal action, not just the shortlist.",
                OllamaClient.stringFieldSchema("unused")));
        return tools;
    }

    /**
     * Answers a read-only inspection call, or returns null if this is not one it
     * recognises (which chooseAction() then treats as a genuine model error, not an
     * advertising bug -- see supportedTools()).
     */
    public interface InspectionAnswerer {
        /**
         * Which of KannaAgent's known inspection tools (the TOOL_* constants) this
         * answerer actually handles. chooseAction() advertises exactly this subset to
         * the model, so declaring a tool here and not handling it in answer() (or vice
         * versa) is the bug this method exists to make impossible.
         */
        Set<String> supportedTools();

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
        Set<String> supported = answerer.supportedTools();
        for (Map.Entry<String, JsonObject> entry : INSPECTION_TOOLS.entrySet()) {
            if (supported.contains(entry.getKey())) {
                tools.add(entry.getValue());
            }
        }

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
