package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The turn-level plan Strategist sets once at the start of Kanna's own turn, which then
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
 * DARRELLBEST-FORK, second attempt: the first shape here was a goal word plus a one-sentence
 * rationale -- "Turn plan (T10): STABILIZE -- I am significantly behind..." -- and it did not
 * work. Measured result: 5/5 runs of the Altar-of-Dementia behavioural test still sacrificed
 * the whole board, and the model's own reasoning trace described its own plan as "likely a
 * hint from the system telling me what I should do", not a commitment it had made. A mood is
 * not checkable against a specific decision. This shape adds two things a decision prompt CAN
 * be checked against: pre-committed {@link #conditionals} ("if X, then Y" -- an if-branch the
 * model already decided before seeing the triggering prompt) and pre-committed
 * {@link #prohibitions} ("do NOT X" -- the only mechanism that has ever been observed to stop
 * Kanna feeding creatures to a free sacrifice outlet one at a time, each one individually
 * justifiable in isolation). Both are capped in code (see {@link #MAX_CONDITIONALS},
 * {@link #MAX_PROHIBITIONS}) rather than merely requested in the prompt, on the same
 * "heuristics compute, the model judges -- but the shape of what it can hand back is not
 * negotiable" principle as the rest of this project.
 * <p>
 * Deliberately still tiny overall: render() is re-sent with every decision prompt for the
 * rest of the turn, exactly like the capped combat history in ComputerPlayerKanna, so its
 * size is a recurring per-decision token cost, not a one-time one. {@link #MAX_RENDER_LENGTH}
 * is the hard backstop on that cost regardless of how verbose any individual field comes back.
 *
 * @author Darrell Best
 */
public final class TurnPlan {

    /** The only goals Strategist may commit to. Anything else is a genuine model error. */
    public static final Set<String> VALID_GOALS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("RACE", "STABILIZE", "DEVELOP", "CONTROL", "MILL")));

    /** At most this many "if X, then Y" contingencies survive into a plan -- excess is trimmed, not rejected. */
    static final int MAX_CONDITIONALS = 3;

    /** At most this many "do NOT X" prohibitions survive into a plan -- excess is trimmed, not rejected. */
    static final int MAX_PROHIBITIONS = 2;

    /**
     * Hard cap, in characters, on the whole string {@link #render()} returns -- the backstop
     * for "over-length entries" from Strategist's validation contract. Applied to the fully
     * assembled string rather than per-field so one verbose field cannot dodge the budget by
     * pushing the overrun into a different field. Chosen to comfortably fit a goal line plus a
     * handful of short sentences without approaching the token pressure that a paragraph-sized
     * plan would add to every decision prompt for the rest of the turn.
     */
    static final int MAX_RENDER_LENGTH = 400;

    private static final String DEFAULT_GOAL = "DEVELOP";
    private static final String DEFAULT_RATIONALE =
            "No plan available this turn -- defaulting to steady development.";

    public final String goal;
    public final String rationale;
    public final List<String> conditionals;
    public final List<String> prohibitions;
    public final int turnNumber;

    private TurnPlan(String goal, String rationale, List<String> conditionals,
                      List<String> prohibitions, int turnNumber) {
        this.goal = goal;
        this.rationale = rationale;
        this.conditionals = conditionals;
        this.prohibitions = prohibitions;
        this.turnNumber = turnNumber;
    }

    /**
     * @return a validated plan, or null if goal is not one of the five known goals -- never
     * throws, so a caller can treat null exactly like any other "model answered badly" case
     * (log, count via metrics, fall back to defaultPlan()). conditionals/prohibitions are never
     * themselves a reason to reject the whole plan: blank/null entries are dropped and an
     * over-long list is silently trimmed to the cap rather than failing the entire turn plan
     * over a model that was merely too enthusiastic about contingencies.
     */
    public static TurnPlan of(String goal, String rationale, List<String> conditionals,
                               List<String> prohibitions, int turnNumber) {
        if (goal == null || !VALID_GOALS.contains(goal)) {
            return null;
        }
        return new TurnPlan(goal, cleanRationale(rationale),
                sanitizeEntries(conditionals, MAX_CONDITIONALS),
                sanitizeEntries(prohibitions, MAX_PROHIBITIONS),
                turnNumber);
    }

    private static String cleanRationale(String rationale) {
        return rationale == null || rationale.trim().isEmpty() ? "" : rationale.trim();
    }

    /**
     * Drops null/blank entries (a model occasionally emits an empty-string array element
     * rather than omitting it) and caps the result at {@code max}, keeping the first
     * {@code max} non-blank entries in the order the model gave them -- earlier entries are
     * assumed to be the ones the model considered first/most important, same assumption
     * ActionRanker makes about ordering elsewhere in this codebase.
     */
    private static List<String> sanitizeEntries(List<String> raw, int max) {
        List<String> cleaned = new ArrayList<String>();
        if (raw != null) {
            for (String entry : raw) {
                if (entry == null) {
                    continue;
                }
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                cleaned.add(trimmed);
                if (cleaned.size() == max) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(cleaned);
    }

    /**
     * The real fallback plan used whenever the planning call fails, times out, returns no
     * tool call, or returns an invalid goal -- never "no plan at all". Goal DEVELOP: safe
     * and inert, the same reasoning ActionRanker's SCORE_OTHER comment gives for why an
     * un-evaluated choice should default to the least committal option rather than a guess.
     * No conditionals or prohibitions: a default plan is explicitly "no real plan was made",
     * and inventing contingencies for a turn the strategist never actually reasoned about
     * would be worse than having none.
     */
    public static TurnPlan defaultPlan(int turnNumber) {
        return new TurnPlan(DEFAULT_GOAL, DEFAULT_RATIONALE,
                Collections.<String>emptyList(), Collections.<String>emptyList(), turnNumber);
    }

    /**
     * The block injected into every decision prompt this turn: a goal line, then one line per
     * conditional, then one line per prohibition. Multi-line by design now (the old one-line
     * "goal -- mood" shape is exactly what this class's javadoc explains did not work) but
     * still hard-capped at {@link #MAX_RENDER_LENGTH} characters total -- truncation is a
     * plain substring cut at that length, deterministic given the same fields, so the same
     * plan always renders identically and a caller never has to guess how much of a
     * conditional or prohibition actually survived.
     * <p>
     * DARRELLBEST-FORK: prohibitions are rendered BEFORE conditionals, not after -- found by
     * running the live Altar-of-Dementia behavioural test against this class's first
     * implementation, which rendered conditionals first. The model's turn-1 plan that run
     * genuinely included "Do NOT sacrifice creatures unnecessarily.", but with conditionals
     * first the 400-char cap sliced it off the tail before it ever reached a decision prompt,
     * and Kanna sacrificed all three creatures to the Altar that same turn with no prohibition
     * in front of her. Conditionals are tactical guidance; prohibitions are the one mechanism
     * this project has that can stop a bad action outright (see class javadoc) -- if the
     * budget cannot hold everything, it is the conditionals that should give way, not the
     * prohibitions. See TurnPlanTest.renderTruncatesConditionalsBeforeDroppingAnyProhibition.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Turn plan (T").append(turnNumber).append("): ").append(goal);
        if (!rationale.isEmpty()) {
            sb.append(" -- ").append(rationale);
        }
        for (String prohibition : prohibitions) {
            sb.append(System.lineSeparator()).append(prohibition);
        }
        for (String conditional : conditionals) {
            sb.append(System.lineSeparator()).append(conditional);
        }
        String rendered = sb.toString();
        return rendered.length() > MAX_RENDER_LENGTH ? rendered.substring(0, MAX_RENDER_LENGTH) : rendered;
    }
}
