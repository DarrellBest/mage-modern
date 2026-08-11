package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which colors to spend first so the plan the model chose stays possible.
 * <p>
 * The model never sees this. It exists because paying a cost badly can strand a
 * color still needed by a card in hand, quietly invalidating the line the model
 * picked -- a loss that never appears in a log, only as wasted turns.
 * <p>
 * DARRELLBEST-FORK: currently unreachable from ComputerPlayerKanna -- this class has
 * 7 passing unit tests but nothing calls it. Investigated wiring it in by overriding
 * {@code Player.playMana(Ability, ManaCost, String, Game)} and calling
 * {@code super.playMana(...)} after biasing source order, and found no seam that
 * would actually work: for a real spell cast, {@code ManaCostsImpl.getUnpaid()}
 * always returns a composite {@code ManaCosts}, and
 * {@code ComputerPlayer.playManaHandling} routes that case to
 * {@code ComputerPlayer.getSortedProducers} -- which is {@code private} (not
 * overridable from this subclass) and picks a source by scoring every candidate into
 * a plain {@code HashMap<MageObject, Integer>} and sorting strictly by that score.
 * {@code HashMap} iteration order is not insertion order, so even overriding the
 * public {@code getAvailableManaProducers(Game)} to reorder its input list has no
 * reliable effect on the end result -- any bias expressed purely as list order is
 * lost before the final sort. The only path that does honor a producer list's order
 * directly is the non-{@code ManaCosts} branch of {@code playManaHandling}, which is
 * not the one used for casting spells (the case this class exists for). The
 * remaining option -- overriding {@code playManaHandling} itself to reimplement
 * source selection -- is exactly what this class was told not to do: replace the
 * engine's payment mechanism rather than merely influence it. So this stays dead
 * code, honestly labelled, rather than a wrapper that pretends to bias a decision it
 * cannot actually reach.
 *
 * @author Darrell Best
 */
public final class ManaPlanner {

    private ManaPlanner() {
    }

    /**
     * @return higher is better. Spending a color that nothing in hand needs costs
     * nothing; spending a scarce needed color costs the most.
     */
    public static int scorePayment(List<String> sourceColors, List<String> colorsNeededInHand) {
        Map<String, Integer> demand = demandByColor(colorsNeededInHand);
        int penalty = 0;
        for (String color : sourceColors) {
            Integer needed = demand.get(color);
            if (needed != null && needed > 0) {
                // scarcer demand is protected harder: needing 1 red penalises more than needing 3 green
                penalty += 100 / needed;
            }
        }
        return -penalty;
    }

    /**
     * @return the available colors ordered cheapest-to-spend first. Stable for
     * equal preference, so payment is reproducible.
     */
    public static List<String> preferredOrder(List<String> availableColors, final List<String> colorsNeededInHand) {
        List<String> order = new ArrayList<String>(availableColors);
        Collections.sort(order, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int scoreA = scorePayment(Collections.singletonList(a), colorsNeededInHand);
                int scoreB = scorePayment(Collections.singletonList(b), colorsNeededInHand);
                return Integer.compare(scoreB, scoreA);
            }
        });
        return order;
    }

    private static Map<String, Integer> demandByColor(List<String> colorsNeededInHand) {
        Map<String, Integer> demand = new HashMap<String, Integer>();
        for (String color : colorsNeededInHand) {
            Integer current = demand.get(color);
            demand.put(color, current == null ? 1 : current + 1);
        }
        return demand;
    }
}
