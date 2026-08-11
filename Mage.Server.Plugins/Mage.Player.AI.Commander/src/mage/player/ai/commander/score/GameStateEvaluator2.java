package mage.player.ai.commander.score;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.UUID;
import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.constants.Outcome;

/**
 * @author nantuko
 * <p>
 * This evaluator is only good for two player games
 * <p>
 * DARRELLBEST-FORK: the weights are no longer baked in here -- they arrive as a
 * {@link CommanderEvalParams}, which the calling player owns. See that class for why, and for what
 * deliberately stayed a constant.
 * <p>
 * The params argument is REQUIRED on every entry point, with no defaulting overload. A convenience
 * overload would silently score with stock weights whenever a caller forgot to pass its own, which is
 * indistinguishable from working. Forcing the argument makes a dropped param a compile error.
 */
public final class GameStateEvaluator2 {

    private static final Logger logger = Logger.getLogger(GameStateEvaluator2.class);

    public static final int WIN_GAME_SCORE = 100000000;
    public static final int LOSE_GAME_SCORE = -WIN_GAME_SCORE;

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game, CommanderEvalParams params) {
        return evaluate(playerId, game, true, params);
    }

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game, boolean useCombatPermanentScore, CommanderEvalParams params) {
        // TODO: add multi opponents support, so AI can take better actions
        Player player = game.getPlayer(playerId);
        // must find all leaved opponents
        Player opponent = game.getPlayer(game.getOpponents(playerId, false).stream().findFirst().orElse(null));
        if (opponent == null) {
            return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        }

        if (game.checkIfGameIsOver()) {
            if (player.hasLost()
                    || opponent.hasWon()) {
                return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
            }
            if (opponent.hasLost()
                    || player.hasWon()) {
                return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
            }
        }

        int playerLifeScore = 0;
        int opponentLifeScore = 0;
        if (player.getLife() <= 0) { // we don't want a tie
            playerLifeScore = ArtificialScoringSystem.LOSE_GAME_SCORE;
        } else if (opponent.getLife() <= 0) {
            playerLifeScore = ArtificialScoringSystem.WIN_GAME_SCORE;
        } else {
            playerLifeScore = ArtificialScoringSystem.getLifeScore(player.getLife(), params);
            opponentLifeScore = ArtificialScoringSystem.getLifeScore(opponent.getLife(), params); // TODO: minus

            // DARRELLBEST-FORK: commander damage is a SECOND death clock the evaluator was blind to.
            // A player on 35 life who has taken 18 commander damage is one connection from losing,
            // and without this term they score as healthy. No value of any life weight can express
            // that, because it is a different axis -- which is why this is a new term rather than a
            // retuned one.
            //
            // Weight defaults to 0, so DEFAULT stays bit-identical to the historical evaluator and
            // this only takes effect for a params set that opts in.
            if (params.getCommanderDamageWeight() != 0) {
                playerLifeScore -= commanderDamagePenalty(player.getId(), game, params);
                opponentLifeScore -= commanderDamagePenalty(opponent.getId(), game, params);
            }
        }

        int playerPermanentsScore = 0;
        int opponentPermanentsScore = 0;
        try {
            StringBuilder sbPlayer = new StringBuilder();
            StringBuilder sbOpponent = new StringBuilder();

            // add values of player
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
                int onePermScore = evaluatePermanent(permanent, game, useCombatPermanentScore, params);
                playerPermanentsScore += onePermScore;
                if (logger.isDebugEnabled()) {
                    sbPlayer.append(permanent.getName()).append('[').append(onePermScore).append("] ");
                }
            }
            if (logger.isDebugEnabled()) {
                sbPlayer.insert(0, playerPermanentsScore + " - ");
                sbPlayer.insert(0, "Player..: ");
                logger.debug(sbPlayer);
            }

            // add values of opponent
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(opponent.getId())) {
                int onePermScore = evaluatePermanent(permanent, game, useCombatPermanentScore, params);
                opponentPermanentsScore += onePermScore;
                if (logger.isDebugEnabled()) {
                    sbOpponent.append(permanent.getName()).append('[').append(onePermScore).append("] ");
                }
            }
            if (logger.isDebugEnabled()) {
                sbOpponent.insert(0, opponentPermanentsScore + " - ");
                sbOpponent.insert(0, "Opponent: ");
                logger.debug(sbOpponent);
            }
        } catch (Throwable t) {
        }

        // TODO: add card evaluator like permanent evaluator
        // - same card on battlefield must score x2 compared to hand, so AI will want to play it;
        // - other zones must score cards same way, example: battlefield = x, hand = x * 0.1, graveyard = x * 0.5, exile = x * 0.3
        // - possible bug in wrong score: instant and sorcery on hand will be more valuable compared to other zones,
        //   so AI will keep it in hand. Possible fix: look at card type and apply zones multipliers due special
        //   table like:
        //   * battlefield needs in creatures and enchantments/auras;
        //   * hand needs in instants and sorceries
        //   * graveyard needs in anything after battlefield and hand;
        //   * exile needs in nothing;
        //   * commander zone needs in nothing;
        // - additional improve: use revealed data to score opponent's hand:
        //   * known card by card evaluator;
        //   * unknown card by max value (so AI will use reveal to make opponent's total score lower -- is it helps???)
        int playerHandScore = player.getHand().size() * params.getHandCardScore();
        int opponentHandScore = opponent.getHand().size() * params.getHandCardScore();

        int score = (playerLifeScore - opponentLifeScore)
                + (playerPermanentsScore - opponentPermanentsScore)
                + (playerHandScore - opponentHandScore);
        logger.debug(score
                + " total Score (life:" + (playerLifeScore - opponentLifeScore)
                + " permanents:" + (playerPermanentsScore - opponentPermanentsScore)
                + " hand:" + (playerHandScore - opponentHandScore) + ')');
        return new PlayerEvaluateScore(
                playerId,
                playerLifeScore, playerHandScore, playerPermanentsScore,
                opponentLifeScore, opponentHandScore, opponentPermanentsScore);
    }

    /**
     * DARRELLBEST-FORK: penalty for how close {@code playerId} is to dying to commander damage.
     * <p>
     * Aggregated as a <b>maximum over commanders, not a sum</b>: the rule is 21 damage from a
     * SINGLE commander, so a player on 15 from one commander and 15 from another is at 15 of 21,
     * not 30 of 21. Summing would have the bot panicking in multi-commander games and, worse,
     * treating a spread-out board as lethal when it is not.
     * <p>
     * Quadratic in damage taken so the term stays near-free early and bites hard approaching
     * lethal, matching how the life curve is steep near death. At the full 21 the penalty equals
     * the weight, which makes the weight directly interpretable: "what is dying to commander
     * damage worth, on the same scale as the life score".
     */
    private static int commanderDamagePenalty(UUID playerId, Game game, CommanderEvalParams params) {
        int worst = 0;
        for (UUID pid : game.getState().getPlayersInRange(playerId, game)) {
            Player other = game.getPlayer(pid);
            if (other == null) {
                continue;
            }
            for (mage.cards.Card commander : game.getCommanderCardsFromAnyZones(
                    other, mage.constants.CommanderCardType.ANY, mage.constants.Zone.BATTLEFIELD,
                    mage.constants.Zone.COMMAND, mage.constants.Zone.GRAVEYARD)) {
                mage.watchers.common.CommanderInfoWatcher watcher = game.getState()
                        .getWatcher(mage.watchers.common.CommanderInfoWatcher.class, commander.getId());
                if (watcher != null) {
                    worst = Math.max(worst, watcher.getDamageToPlayer().getOrDefault(playerId, 0));
                }
            }
        }
        if (worst <= 0) {
            return 0;
        }
        int lethal = 21;
        int capped = Math.min(worst, lethal);
        return params.getCommanderDamageWeight() * capped * capped / (lethal * lethal);
    }

    public static int evaluatePermanent(Permanent permanent, Game game, boolean useCombatPermanentScore, CommanderEvalParams params) {
        // prevent AI from attaching bad auras to its own permanents ex: Brainwash and Demonic Torment (no immediate penalty on the battlefield)
        int value = 0;
        if (!permanent.getAttachments().isEmpty()) {
            for (UUID attachmentId : permanent.getAttachments()) {
                Permanent attachment = game.getPermanent(attachmentId);
                for (Ability a : attachment.getAbilities(game)) {
                    for (Effect e : a.getEffects()) {
                        if (e.getOutcome().equals(Outcome.Detriment)
                                && attachment.getControllerId().equals(permanent.getControllerId())) {
                            value += params.getDetrimentalOwnAuraPenalty();  // seems to work well ; -300 is not effective enough
                        }
                    }
                }
            }
        }
        value += ArtificialScoringSystem.getFixedPermanentScore(game, permanent, params);
        value += ArtificialScoringSystem.getDynamicPermanentScore(game, permanent, params);
        if (useCombatPermanentScore) {
            value += ArtificialScoringSystem.getCombatPermanentScore(game, permanent, params);
        }
        return value;
    }

    public static class PlayerEvaluateScore {

        private UUID playerId;
        private int playerLifeScore = 0;
        private int playerHandScore = 0;
        private int playerPermanentsScore = 0;

        private int opponentLifeScore = 0;
        private int opponentHandScore = 0;
        private int opponentPermanentsScore = 0;

        private int specialScore = 0; // special score (ignore all others, e.g. for win/lose game states)

        public PlayerEvaluateScore(UUID playerId, int specialScore) {
            this.playerId = playerId;
            this.specialScore = specialScore;
        }

        public PlayerEvaluateScore(UUID playerId,
                                   int playerLifeScore, int playerHandScore, int playerPermanentsScore,
                                   int opponentLifeScore, int opponentHandScore, int opponentPermanentsScore) {
            this.playerId = playerId;
            this.playerLifeScore = playerLifeScore;
            this.playerHandScore = playerHandScore;
            this.playerPermanentsScore = playerPermanentsScore;
            this.opponentLifeScore = opponentLifeScore;
            this.opponentHandScore = opponentHandScore;
            this.opponentPermanentsScore = opponentPermanentsScore;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public int getPlayerScore() {
            return playerLifeScore + playerHandScore + playerPermanentsScore;
        }

        public int getOpponentScore() {
            return opponentLifeScore + opponentHandScore + opponentPermanentsScore;
        }

        public int getTotalScore() {
            if (specialScore != 0) {
                return specialScore;
            } else {
                return getPlayerScore() - getOpponentScore();
            }
        }

        public int getPlayerLifeScore() {
            return playerLifeScore;
        }

        public int getPlayerHandScore() {
            return playerHandScore;
        }

        public int getPlayerPermanentsScore() {
            return playerPermanentsScore;
        }

        public String getPlayerInfoFull() {
            return "Life:" + playerLifeScore
                    + ", Hand:" + playerHandScore
                    + ", Perm:" + playerPermanentsScore;
        }

        public String getPlayerInfoShort() {
            return "L:" + playerLifeScore
                    + ",H:" + playerHandScore
                    + ",P:" + playerPermanentsScore;
        }

        public String getOpponentInfoFull() {
            return "Life:" + opponentLifeScore
                    + ", Hand:" + opponentHandScore
                    + ", Perm:" + opponentPermanentsScore;
        }

        public String getOpponentInfoShort() {
            return "L:" + opponentLifeScore
                    + ",H:" + opponentHandScore
                    + ",P:" + opponentPermanentsScore;
        }
    }
}
