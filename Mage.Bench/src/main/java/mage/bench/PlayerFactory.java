package mage.bench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.players.Player;

/**
 * Builds a benchmark player from a short type key, so any matchup is
 * expressible from the command line without code changes. Kanna-vs-stock and
 * stock-vs-stock therefore share one code path, which is what makes the
 * stock-vs-stock control run a trustworthy check on the harness itself.
 *
 * @author Darrell Best
 */
public final class PlayerFactory {

    public static final String KANNA = "kanna";
    public static final String CP7 = "cp7";
    public static final String MCTS = "mcts";
    public static final String BASE = "base";

    private PlayerFactory() {
    }

    public static Player create(String type, String name, RangeOfInfluence range, int skill) {
        if (KANNA.equals(type)) {
            return new ComputerPlayerKanna(name, range, skill);
        } else if (CP7.equals(type)) {
            return new ComputerPlayer7(name, range, skill);
        } else if (MCTS.equals(type)) {
            return new ComputerPlayerMCTS(name, range, skill);
        } else if (BASE.equals(type)) {
            return new ComputerPlayer(name, range);
        }
        throw new IllegalArgumentException("Unknown player type '" + type
                + "', expected one of: " + KANNA + ", " + CP7 + ", " + MCTS + ", " + BASE);
    }
}
