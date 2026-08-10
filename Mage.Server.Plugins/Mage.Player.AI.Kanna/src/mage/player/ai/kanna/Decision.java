package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the agent concluded. `fallback` means the model did not produce a usable
 * answer and the heuristic layer should decide instead -- which is different from
 * the model deliberately choosing to do nothing.
 *
 * @author Darrell Best
 */
public final class Decision {

    public final String chosenId;
    public final List<String[]> pairs;
    public final boolean fallback;

    private Decision(String chosenId, List<String[]> pairs, boolean fallback) {
        this.chosenId = chosenId;
        this.pairs = pairs == null
                ? Collections.<String[]>emptyList()
                : Collections.unmodifiableList(new ArrayList<String[]>(pairs));
        this.fallback = fallback;
    }

    public static Decision of(String chosenId) {
        return new Decision(chosenId, null, false);
    }

    public static Decision ofPairs(List<String[]> pairs) {
        return new Decision(null, pairs, false);
    }

    public static Decision fallback() {
        return new Decision(null, null, true);
    }
}
