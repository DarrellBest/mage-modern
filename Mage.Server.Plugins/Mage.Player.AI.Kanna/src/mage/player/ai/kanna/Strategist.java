package mage.player.ai.kanna;

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
 * Never returns null: a failed, timed-out, tool-call-less, or invalid-goal response all
 * degrade to {@link TurnPlan#defaultPlan(int)}, exactly like KannaAgent.chooseAction never
 * returning "do nothing" as a way of failing.
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
     * (no tool call, wrong tool, missing/invalid goal). Deliberately excludes the
     * transport-exception path, same reasoning as KannaAgent.getInvalidCount(): that is
     * infrastructure failure, not the model answering badly.
     */
    public int getInvalidCount() {
        return invalidCount;
    }

    public TurnPlan plan(String prompt, int turnNumber) {
        List<JsonObject> tools = new ArrayList<JsonObject>();
        tools.add(OllamaClient.tool(TOOL_COMMIT_PLAN,
                "Commit to this turn's single strategic goal.",
                OllamaClient.stringFieldSchema("goal", "rationale")));

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
        TurnPlan plan = TurnPlan.of(goal, rationale, turnNumber);
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
}
