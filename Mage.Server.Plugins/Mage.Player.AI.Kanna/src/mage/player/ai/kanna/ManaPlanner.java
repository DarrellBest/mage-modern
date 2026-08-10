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
