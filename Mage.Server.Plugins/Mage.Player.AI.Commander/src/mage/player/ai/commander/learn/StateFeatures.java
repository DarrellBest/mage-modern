package mage.player.ai.commander.learn;

import mage.constants.CardType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.UUID;

/**
 * DARRELLBEST-FORK: turns a game state into a fixed-length vector of numbers, so a model can be
 * trained to score positions instead of the score coming from hand-tuned constants.
 * <p>
 * Every feature is a DIFFERENTIAL (mine minus the opponents'), for two reasons. It halves the
 * parameter count, and more importantly it builds in the symmetry the game actually has: a position
 * is good for me exactly as much as its mirror is good for my opponent. A model trained on absolute
 * counts has to learn that symmetry from data, and with limited data it learns it imperfectly and
 * plays asymmetrically.
 * <p>
 * <b>Only public information.</b> Hand SIZE is public; hand CONTENTS are not, and neither is library
 * order. Nothing here reads either. This matters more than it looks: the AI evaluates simulated
 * states where the engine may well permit peeking, and a model trained on features it can see in
 * simulation but not in a real game learns to play like a cheater and then plays badly when the
 * information is gone. The restriction is a correctness requirement, not an ethics one.
 * <p>
 * Feature scaling is deliberately crude (raw counts, not normalised). Logistic regression handles
 * unscaled inputs by learning small coefficients; keeping the raw values makes the learned weights
 * directly comparable to the hand-tuned constants they replace, which is what makes the first
 * trained model auditable ("it decided life is worth 12 cards, not 60").
 *
 * @author Darrell Best
 */
public final class StateFeatures {

    /**
     * Feature names, in the exact order {@link #extract} emits them. Weight files are keyed by
     * position, so this order is a wire format: appending is safe, reordering or removing silently
     * mismatches every previously trained weight vector against the wrong feature.
     */
    public static final String[] NAMES = {
            "life_diff",
            "hand_size_diff",
            "creature_count_diff",
            "creature_power_diff",
            "creature_toughness_diff",
            "land_count_diff",
            "untapped_land_diff",
            "artifact_count_diff",
            "enchantment_count_diff",
            "planeswalker_count_diff",
            "commander_on_battlefield_diff",
            "library_size_diff",
            "turn_number",
    };

    public static final int SIZE = NAMES.length;

    private StateFeatures() {
    }

    /**
     * @param playerId the player to score for; all differentials are from this player's view
     * @return a vector of length {@link #SIZE}, or null when the player is not in the game
     */
    public static double[] extract(UUID playerId, Game game) {
        Player me = game.getPlayer(playerId);
        if (me == null) {
            return null;
        }

        double[] f = new double[SIZE];
        int oppCount = 0;
        int oppLife = 0;
        int oppHand = 0;
        int oppLibrary = 0;
        for (UUID oppId : game.getOpponents(playerId)) {
            Player opp = game.getPlayer(oppId);
            if (opp == null) {
                continue;
            }
            oppCount++;
            oppLife += opp.getLife();
            oppHand += opp.getHand().size();
            oppLibrary += opp.getLibrary().size();
        }
        // Average rather than sum across opponents, so a feature means the same thing in a duel and
        // in a 4-player game. Summing would make "opponent life" three times larger in multiplayer
        // and a model trained mostly on duels would read every multiplayer board as catastrophic.
        double div = Math.max(oppCount, 1);

        f[0] = me.getLife() - oppLife / div;
        f[1] = me.getHand().size() - oppHand / div;

        int[] mine = new int[8];
        int[] theirs = new int[8];
        for (Permanent p : game.getBattlefield().getAllActivePermanents()) {
            boolean isMine = playerId.equals(p.getControllerId());
            int[] bucket = isMine ? mine : theirs;
            if (p.isCreature(game)) {
                bucket[0]++;
                bucket[1] += Math.max(p.getPower().getValue(), 0);
                bucket[2] += Math.max(p.getToughness().getValue(), 0);
            }
            if (p.isLand(game)) {
                bucket[3]++;
                if (!p.isTapped()) {
                    bucket[4]++;
                }
            }
            if (p.getCardType(game).contains(CardType.ARTIFACT)) {
                bucket[5]++;
            }
            if (p.getCardType(game).contains(CardType.ENCHANTMENT)) {
                bucket[6]++;
            }
            if (p.getCardType(game).contains(CardType.PLANESWALKER)) {
                bucket[7]++;
            }
        }
        for (int i = 0; i < 8; i++) {
            f[2 + i] = mine[i] - theirs[i] / div;
        }

        f[10] = commanderOnBattlefield(playerId, game) - othersCommandersOnBattlefield(playerId, game) / div;
        f[11] = me.getLibrary().size() - oppLibrary / div;
        f[12] = game.getTurnNum();
        return f;
    }

    private static int commanderOnBattlefield(UUID playerId, Game game) {
        int count = 0;
        for (Permanent p : game.getBattlefield().getAllActivePermanents()) {
            if (playerId.equals(p.getControllerId()) && game.getCommanderCardsFromAnyZones(
                    game.getPlayer(playerId), null, null).stream()
                    .anyMatch(c -> c.getId().equals(p.getId()))) {
                count++;
            }
        }
        return count;
    }

    private static int othersCommandersOnBattlefield(UUID playerId, Game game) {
        int count = 0;
        for (UUID oppId : game.getOpponents(playerId)) {
            count += commanderOnBattlefield(oppId, game);
        }
        return count;
    }
}
