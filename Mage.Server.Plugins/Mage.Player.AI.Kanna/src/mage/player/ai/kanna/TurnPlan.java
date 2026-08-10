package mage.player.ai.kanna;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The turn-level goal Strategist sets once at the start of Kanna's own turn, which then
 * rides along in every per-decision prompt for the rest of that turn.
 * <p>
 * Exists because per-decision judgement alone cannot see scale: shown "sacrifices a
 * creature -- board 1 -> 0" in isolation, Kanna activated Altar of Dementia anyway, three
 * times in one game, because milling ~9 cards out of a ~90-card library reads as progress
 * toward a real win condition when nothing in the prompt says how far away that win
 * actually is. A TurnPlan does not fix that by hiding or reordering options (that is the
 * ranker's job, and a ranker silently promoting things it does not understand is exactly
 * the bug that motivated this project's "heuristics compute, the model judges" split in
 * the first place) -- it fixes it by handing the model, once per turn, the scale
 * information (both library sizes, both life totals, both boards) that a single
 * mid-combat prompt never carries, so the same model can judge for itself whether MILL is
 * actually live this turn.
 * <p>
 * Deliberately tiny: render() is re-sent with every decision prompt for the rest of the
 * turn, exactly like the capped combat history in ComputerPlayerKanna, so its size is a
 * recurring per-decision token cost, not a one-time one.
 *
 * @author Darrell Best
 */
public final class TurnPlan {

    /** The only goals Strategist may commit to. Anything else is a genuine model error. */
    public static final Set<String> VALID_GOALS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("RACE", "STABILIZE", "DEVELOP", "CONTROL", "MILL")));

    private static final String DEFAULT_GOAL = "DEVELOP";
    private static final String DEFAULT_RATIONALE =
            "No plan available this turn -- defaulting to steady development.";

    public final String goal;
    public final String rationale;
    public final int turnNumber;

    private TurnPlan(String goal, String rationale, int turnNumber) {
        this.goal = goal;
        this.rationale = rationale;
        this.turnNumber = turnNumber;
    }

    /**
     * @return a validated plan, or null if goal is not one of the five known goals --
     * never throws, so a caller can treat null exactly like any other "model answered
     * badly" case (log, count via metrics, fall back to defaultPlan()).
     */
    public static TurnPlan of(String goal, String rationale, int turnNumber) {
        if (goal == null || !VALID_GOALS.contains(goal)) {
            return null;
        }
        return new TurnPlan(goal, rationale == null || rationale.trim().isEmpty()
                ? "" : rationale.trim(), turnNumber);
    }

    /**
     * The real fallback plan used whenever the planning call fails, times out, returns no
     * tool call, or returns an invalid goal -- never "no plan at all". Goal DEVELOP: safe
     * and inert, the same reasoning ActionRanker's SCORE_OTHER comment gives for why an
     * un-evaluated choice should default to the least committal option rather than a guess.
     */
    public static TurnPlan defaultPlan(int turnNumber) {
        return new TurnPlan(DEFAULT_GOAL, DEFAULT_RATIONALE, turnNumber);
    }

    /**
     * Compact single line injected into every decision prompt this turn. Kept to one goal
     * word plus one sentence on purpose -- see the class javadoc on recurring token cost.
     */
    public String render() {
        return "Turn plan (T" + turnNumber + "): " + goal
                + (rationale.isEmpty() ? "" : " -- " + rationale);
    }
}
