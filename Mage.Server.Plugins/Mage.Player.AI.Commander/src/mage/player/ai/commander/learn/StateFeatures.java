package mage.player.ai.commander.learn;

import mage.constants.CardType;
import mage.constants.CommanderCardType;
import java.util.Set;
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
            // DARRELLBEST-FORK: appended (never reordered -- the order above is a wire format, and
            // an old weights file must keep lining up with the features it was trained on).
            //
            // These mirror the evaluator terms of the same name, which is the point of the design:
            // the learner tunes weights over the SAME basis the hand-tuned bot reasons in, so what
            // it learns is directly interpretable as "this term mattered more than we thought"
            // rather than living in a parallel feature space nobody can reconcile.
            "deployed_mana_value_diff",
            "unspent_mana_own_turn",
            "draw_engine_count_diff",
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

        // DARRELLBEST-FORK: appended features, mirroring the evaluator terms of the same names.
        double myDeployed = 0;
        double myDraw = 0;
        int myUnspent = 0;
        boolean ownMain = playerId.equals(game.getActivePlayerId())
                && (game.getTurnStepType() == mage.constants.PhaseStep.PRECOMBAT_MAIN
                    || game.getTurnStepType() == mage.constants.PhaseStep.POSTCOMBAT_MAIN);
        for (mage.game.permanent.Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
            myDeployed += p.getManaValue();
            boolean mana = false;
            for (mage.abilities.Ability a : p.getAbilities(game)) {
                if (a instanceof mage.abilities.mana.ManaAbility) {
                    mana = true;
                }
                for (mage.abilities.effects.Effect e : a.getAllEffects()) {
                    if (e.getClass().getSimpleName().contains("DrawCard")) {
                        myDraw++;
                        break;
                    }
                }
            }
            if (mana && !p.isTapped() && ownMain) {
                myUnspent++;
            }
        }
        double oppDeployed = 0;
        double oppDraw = 0;
        int seen = 0;
        for (UUID oppId2 : game.getOpponents(playerId)) {
            if (game.getPlayer(oppId2) == null) {
                continue;
            }
            seen++;
            for (mage.game.permanent.Permanent p : game.getBattlefield().getAllActivePermanents(oppId2)) {
                oppDeployed += p.getManaValue();
                for (mage.abilities.Ability a : p.getAbilities(game)) {
                    for (mage.abilities.effects.Effect e : a.getAllEffects()) {
                        if (e.getClass().getSimpleName().contains("DrawCard")) {
                            oppDraw++;
                            break;
                        }
                    }
                }
            }
        }
        // averaged across opponents for the same reason the existing features are: a feature must
        // mean the same thing in a duel and in a four-player pod.
        double oppDiv = seen == 0 ? 1 : seen;
        f[13] = myDeployed - (oppDeployed / oppDiv);
        f[14] = myUnspent;
        f[15] = myDraw - (oppDraw / oppDiv);
        return f;
    }

    /**
     * DARRELLBEST-FORK: how many of this player's commanders are on the battlefield.
     * <p>
     * This threw a NullPointerException on every call that reached it, which is what "the learner
     * AI is broken" turned out to be. The old code called
     * {@code getCommanderCardsFromAnyZones(player, null, null)}; the third parameter is a
     * {@code Zone...} varargs, and an explicit null becomes a null array, so the method's own
     * {@code Arrays.stream(searchZones)} threw immediately. It only reached that call once the
     * player controlled a permanent, so opening turns looked fine and the failure appeared later.
     * <p>
     * The exception was swallowed upstream: games continued, a weights file was still written, and
     * nothing reported an error -- so the learner appeared to run while its feature vector was
     * never actually produced. Any weights trained before this fix are meaningless.
     * <p>
     * Uses getCommandersIds directly, which is also cheaper: the old version re-queried every
     * commander for every permanent, inside a method called on every state evaluation.
     */
    private static int commanderOnBattlefield(UUID playerId, Game game) {
        Player player = game.getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        Set<UUID> commanderIds = game.getCommandersIds(player, CommanderCardType.ANY, true);
        if (commanderIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (commanderIds.contains(p.getId())
                    || (p.getMainCard() != null && commanderIds.contains(p.getMainCard().getId()))) {
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
