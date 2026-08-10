package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The one extra LLM call per Kanna turn: builds the planning prompt, makes the call, and
 * validates the result into a {@link TurnPlan}. Mirrors {@link KannaAgent}'s failure
 * conventions deliberately (same tool-call mechanism via OllamaClient.tool(...), same
 * "transport failure vs genuine model error" split for what counts toward invalidCount)
 * rather than inventing a second way for a model call to fail -- see getInvalidCount()
 * below for the same caveat KannaAgent.getInvalidCount() documents.
 * <p>
 * Never returns null: a failed, timed-out, tool-call-less, invalid-goal, or malformed-shape
 * response all degrade to {@link TurnPlan#defaultPlan(int)}, exactly like KannaAgent.chooseAction
 * never returning "do nothing" as a way of failing.
 *
 * @author Darrell Best
 */
public final class Strategist {

    private static final Logger logger = Logger.getLogger(Strategist.class);

    public static final String TOOL_COMMIT_PLAN = "commit_plan";

    private final OllamaClient client;
    private int invalidCount = 0;

    public Strategist(OllamaClient client) {
        this.client = client;
    }

    /**
     * @return how many times the model gave a genuinely bad answer for the planning call
     * (no tool call, wrong tool, missing/invalid goal, or a malformed conditionals/prohibitions
     * shape). Deliberately excludes the transport-exception path, same reasoning as
     * KannaAgent.getInvalidCount(): that is infrastructure failure, not the model answering
     * badly.
     */
    public int getInvalidCount() {
        return invalidCount;
    }

    public TurnPlan plan(String prompt, int turnNumber) {
        List<JsonObject> tools = new ArrayList<JsonObject>();
        tools.add(OllamaClient.tool(TOOL_COMMIT_PLAN,
                "Commit to this turn's goal, pre-committed contingencies, and prohibitions.",
                OllamaClient.stringAndArrayFieldSchema(
                        new String[]{"goal", "rationale"},
                        new String[]{"conditionals", "prohibitions"})));

        ToolCall call;
        try {
            call = client.call(prompt, tools);
        } catch (Exception e) {
            logger.warn("Kanna: strategist transport failure, defaulting the turn plan - " + e);
            return TurnPlan.defaultPlan(turnNumber);
        }
        if (call == null) {
            logger.warn("Kanna: strategist got no tool call, defaulting the turn plan");
            invalidCount++;
            return TurnPlan.defaultPlan(turnNumber);
        }
        if (!TOOL_COMMIT_PLAN.equals(call.name)) {
            logger.warn("Kanna: strategist got unexpected tool '" + call.name + "', defaulting the turn plan");
            invalidCount++;
            return TurnPlan.defaultPlan(turnNumber);
        }
        String goal = optString(call.arguments, "goal");
        String rationale = optString(call.arguments, "rationale");
        List<String> conditionals;
        List<String> prohibitions;
        try {
            conditionals = optStringArray(call.arguments, "conditionals");
            prohibitions = optStringArray(call.arguments, "prohibitions");
        } catch (RuntimeException e) {
            // conditionals/prohibitions present but not an array of strings (e.g. the model
            // sent a bare string, or an array of objects) -- a malformed shape, not a
            // transport problem, so it counts toward invalidCount same as any other genuine
            // model error rather than escaping as an exception.
            logger.warn("Kanna: strategist returned malformed conditionals/prohibitions, "
                    + "defaulting the turn plan - " + e);
            invalidCount++;
            return TurnPlan.defaultPlan(turnNumber);
        }
        TurnPlan plan = TurnPlan.of(goal, rationale, conditionals, prohibitions, turnNumber);
        if (plan == null) {
            logger.warn("Kanna: strategist chose an invalid goal '" + goal + "', defaulting the turn plan");
            invalidCount++;
            return TurnPlan.defaultPlan(turnNumber);
        }
        return plan;
    }

    private static String optString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }

    // DARRELLBEST-FORK: returns an empty list (not null) whenever the field is simply absent
    // or null -- a turn with no live contingencies/prohibitions is a legitimate answer, see
    // stringAndArrayFieldSchema's comment on why these fields are not marked required. Throws
    // (uncaught here, deliberately -- see the try/catch in plan() above) only when the field
    // is PRESENT but the wrong shape, which is the genuine malformed-response case.
    private static List<String> optStringArray(JsonObject object, String field) {
        List<String> result = new ArrayList<String>();
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return result;
        }
        JsonArray array = object.get(field).getAsJsonArray();
        for (JsonElement element : array) {
            if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }
}
