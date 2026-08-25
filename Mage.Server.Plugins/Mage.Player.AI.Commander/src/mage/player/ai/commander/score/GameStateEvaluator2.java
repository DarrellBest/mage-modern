package mage.player.ai.commander.score;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.UUID;
import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.constants.PhaseStep;
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
        Player player = game.getPlayer(playerId);
        // DARRELLBEST-FORK: upstream took the FIRST opponent and ignored the rest (its own TODO said
        // "add multi opponents support"). Harmless in a duel, badly wrong in a Free For All, which
        // is what the live server actually runs -- the bot scores the board as though two of its
        // three opponents do not exist. See CommanderEvalParams.getOpponentSelectionMode; mode 0 is
        // the original behaviour and remains the default.
        Player opponent = selectOpponent(playerId, game, useCombatPermanentScore, params);
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

        // DARRELLBEST-FORK: board SHAPE, accumulated inside the two permanent walks that already run
        // rather than in walks of their own. Three terms below need it -- must-answer, overextension
        // and commander recast -- and none of them is worth a third and fourth pass over the
        // battlefield at every search leaf. Declared out here because the folding happens after the
        // try; a partial count from an aborted walk is the same failure mode the permanent scores
        // themselves already have.
        boolean needBoardShape = params.getMustAnswerBonus() != 0
                || params.getOverextensionPenalty() != 0;
        int myCreatureCount = 0;
        int myCreaturePower = 0;
        int opponentCreatureCount = 0;
        int opponentCreaturePower = 0;
        int mustAnswerSignals = 0;
        boolean ownCommanderOnBattlefield = false;
        try {
            StringBuilder sbPlayer = new StringBuilder();
            StringBuilder sbOpponent = new StringBuilder();

            // add values of player
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
                int onePermScore = evaluatePermanent(permanent, game, useCombatPermanentScore, params);
                playerPermanentsScore += onePermScore;
                if (needBoardShape && permanent.isCreature(game)) {
                    myCreatureCount++;
                    // Negative power exists (Giant Growth's evil twin, -X/-X effects) and would drag
                    // the average below what any real creature has; clamped for the same reason
                    // getDynamicPermanentScore clamps toughness.
                    myCreaturePower += Math.max(permanent.getPower().getValue(), 0);
                }
                if (params.getCommanderRecastPenalty() != 0
                        && !ownCommanderOnBattlefield
                        && game.isCommanderObject(player, permanent)) {
                    // Ours, not merely a commander: isCommanderObject is asked about THIS player, so
                    // an opponent's commander we have stolen does not count as ours being safe.
                    ownCommanderOnBattlefield = true;
                }
            }
            if (logger.isDebugEnabled()) {
                sbPlayer.insert(0, playerPermanentsScore + " - ");
                sbPlayer.insert(0, "Player..: ");
                logger.debug(sbPlayer);
            }

            // The threshold an opponent's creature has to clear to count as outclassing OUR board.
            // It can only be computed once the player's own walk above has finished, which is why
            // the must-answer signals are counted in the opponent walk below and not inside
            // evaluatePermanent -- evaluatePermanent is handed one permanent and knows nothing about
            // the board it sits opposite.
            int outclassThreshold = ArtificialScoringSystem.getOutclassThreshold(
                    myCreatureCount, myCreaturePower);

            // add values of opponent
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(opponent.getId())) {
                int onePermScore = evaluatePermanent(permanent, game, useCombatPermanentScore, params);
                opponentPermanentsScore += onePermScore;
                if (needBoardShape && permanent.isCreature(game)) {
                    opponentCreatureCount++;
                    opponentCreaturePower += Math.max(permanent.getPower().getValue(), 0);
                }
                if (params.getMustAnswerBonus() != 0) {
                    mustAnswerSignals += ArtificialScoringSystem.getMustAnswerSignals(
                            game, permanent, outclassThreshold);
                }
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

        // DARRELLBEST-FORK: count what is waiting on the stack.
        //
        // Without this the stack is invisible, so casting a spell is a pure loss at the moment it
        // happens -- the card leaves hand (-handCardScore), lands tap, and the spell itself scores
        // nothing until it resolves. ComputerPlayer6.addActions abandons a branch whose score drops,
        // so the search declined to explore casting at all: the bot held a full hand and untapped
        // mana, then used the opponent's turn to crack Clues and tap lands, which cost it no card
        // from hand and therefore looked better.
        //
        // A spell is scored as the card it will become, which makes casting roughly score-neutral.
        // An ability is scored at a flat weight, signed by controller -- our own triggers are
        // pending value, an opponent's are pending problems, and a board that triggers many times
        // is genuinely a good position.
        int stackScore = 0;
        if (params.getStackObjectWeight() != 0) {
            for (mage.game.stack.StackObject stackObject : game.getStack()) {
                int worth;
                if (stackObject instanceof mage.game.stack.Spell) {
                    worth = ArtificialScoringSystem.getCardDefinitionScore(
                            game, ((mage.game.stack.Spell) stackObject).getCard(), params);
                } else {
                    worth = params.getStackObjectWeight();
                }
                stackScore += stackObject.getControllerId().equals(playerId) ? worth : -worth;
            }
        }

        // DARRELLBEST-FORK: board development and wasted tempo -- neither expressible by any
        // existing weight. See CommanderEvalParams for the logged behaviour behind each.
        // Own side and opponent side are both needed. Until now only the own side was computed, and
        // a one-sided development term in an otherwise differential evaluator makes the bot value
        // its own board twice and the opponent's not at all -- it would happily fall behind on board
        // as long as it kept deploying.
        int developmentScore = 0;
        int opponentDevelopmentScore = 0;
        if (params.getDeployedManaValueWeight() != 0) {
            for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
                developmentScore += p.getManaValue() * params.getDeployedManaValueWeight();
            }
            for (Permanent p : game.getBattlefield().getAllActivePermanents(opponent.getId())) {
                opponentDevelopmentScore += p.getManaValue() * params.getDeployedManaValueWeight();
            }
        }
        // DARRELLBEST-FORK: untapped mana is a RESOURCE, so spending it costs its value. Without
        // this, activating an ability was free and the bot burned mana on no-ops -- Mutavault
        // re-animated 2026 times in 10 games, sacrifice outlets fired ~490 times each.
        //
        // Counted in every phase and on every turn, unlike the penalty below: mana you still have
        // is worth something whoever's turn it is, which is also what makes holding up an instant
        // score sensibly instead of looking like waste.
        // One pass counts untapped mana sources for both terms below. It used to be two separate
        // walks of the same battlefield running the same predicate, at every search leaf.
        boolean ownMainPhase = playerId.equals(game.getActivePlayerId())
                && (game.getTurnStepType() == PhaseStep.PRECOMBAT_MAIN
                    || game.getTurnStepType() == PhaseStep.POSTCOMBAT_MAIN);
        boolean needSources = params.getManaSourceValue() != 0
                || params.getCommanderRecastPenalty() != 0
                || (params.getUnspentManaPenalty() != 0 && ownMainPhase);

        int openSources = 0;
        int opponentOpenSources = 0;
        if (needSources) {
            for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
                if (!p.isTapped() && hasManaAbility(p, game)) {
                    openSources++;
                }
            }
            if (params.getManaSourceValue() != 0) {
                for (Permanent p : game.getBattlefield().getAllActivePermanents(opponent.getId())) {
                    if (!p.isTapped() && hasManaAbility(p, game)) {
                        opponentOpenSources++;
                    }
                }
            }
        }

        int manaValueScore = openSources * params.getManaSourceValue();
        int opponentManaValueScore = opponentOpenSources * params.getManaSourceValue();

        // Deliberately one-sided, unlike the two terms above: this is the penalty for ending YOUR
        // OWN main phase with mana still open. "The opponent has untapped lands on my turn" is not
        // waste, it is held-up interaction, and is already priced by manaValueScore.
        int unspentScore = 0;
        if (params.getUnspentManaPenalty() != 0 && ownMainPhase) {
            unspentScore = -openSources * params.getUnspentManaPenalty();
        }

        // DARRELLBEST-FORK: THREAT QUALITY. See CommanderEvalParams.getMustAnswerBonus for why raw
        // P/T plus a keyword table cannot express "this is the permanent that wins the game".
        //
        // Charged on the OPPONENT's side, which is what makes removal want it gone: getTotalScore is
        // player minus opponent, so anything added here is score the bot recovers by killing the
        // permanent that carries it. Nothing is added on our own side -- see the getter.
        int mustAnswerScore = 0;
        if (params.getMustAnswerBonus() != 0) {
            int signals = mustAnswerSignals;
            // The fifth signal, and the only one that cannot be judged from a single permanent: a
            // board whose total power already matches our remaining life is a board where every
            // creature is part of a lethal attack, whatever each one looks like on its own. This is
            // the "contributes to lethality" half of principle 6, and it is free here -- both
            // quantities were accumulated in the walk above.
            if (opponentCreaturePower > 0 && opponentCreaturePower >= player.getLife()) {
                signals += opponentCreatureCount;
            }
            mustAnswerScore = signals * params.getMustAnswerBonus();
        }

        // DARRELLBEST-FORK: OVEREXTENSION. deployedManaValueWeight pays for every permanent put onto
        // the battlefield with no ceiling, so emptying the hand always scored better than keeping
        // anything in it. Principles 27, 28 and 38: in a pod the marginal creature past a winning
        // board buys almost nothing and is mostly sweeper fuel.
        //
        // Charged per creature past (their creatures + margin), which is a DIMINISHING RETURN on
        // deployment rather than a cap: the surplus creature still scores its full permanent value,
        // it just scores less of the development bonus. It is deliberately not a wipe-risk model --
        // see the getter for why "the opponent is holding a sweeper" is not knowable from the public
        // board and pretending otherwise fires against every control deck and never against the one
        // holding the card.
        //
        // Own side only, and measured against THIS opponent's creature count: with
        // opponentSelectionMode 1 that is the most threatening opponent, which is the board the bot
        // actually has to get through, and it keeps the term on the same one-opponent footing as
        // every other differential here.
        int overextensionScore = 0;
        if (params.getOverextensionPenalty() != 0) {
            int surplus = myCreatureCount - opponentCreatureCount
                    - ArtificialScoringSystem.OVEREXTENSION_MARGIN;
            if (surplus > 0) {
                overextensionScore = surplus * params.getOverextensionPenalty();
            }
        }

        // DARRELLBEST-FORK: COMMANDER RECAST ECONOMICS (principles 34 and 35).
        //
        // Commander tax is +2 generic per previous cast from the command zone (rule 903.8) and the
        // evaluator had no concept of it: a commander in the command zone was just an absent
        // permanent, priced identically whether rebuying it costs four mana or ten. So the bot could
        // not tell a cheap commander it can redeploy at will from an expensive one it will never see
        // again, and had no reason to protect the second any harder than the first.
        //
        // Priced as the tax it CANNOT currently pay, not as the tax: a commander that died with the
        // mana still up is a tempo loss the rest of the evaluator already sees, while one that died
        // at a tax beyond its controller's mana is stranded. openSources is the same untapped-source
        // proxy manaSourceValue uses -- a count of sources, not of mana, so a Sol Ring counts once;
        // it is an approximation in the safe direction, since it under-states available mana and so
        // under-states nothing but the penalty's own relief.
        //
        // Stacks with commanderPermanentBonus rather than replacing it: that term is the flat value
        // of having the commander out, this one is the part that GROWS with each recast. Both are
        // kept modest because the total cost of losing the commander is their sum.
        int commanderRecastScore = 0;
        if (params.getCommanderRecastPenalty() != 0 && !ownCommanderOnBattlefield) {
            mage.watchers.common.CommanderPlaysCountWatcher plays = game.getState()
                    .getWatcher(mage.watchers.common.CommanderPlaysCountWatcher.class);
            // Absent in a non-commander game, which is a legitimate state for this evaluator to be
            // asked about -- ComputerPlayerCommander is selectable for any format.
            int castCount = plays == null ? 0 : plays.getPlayerCount(playerId);
            if (castCount > 0) {
                int unpayableTax = ArtificialScoringSystem.COMMANDER_TAX_PER_CAST * castCount
                        - openSources;
                if (unpayableTax > 0) {
                    commanderRecastScore = unpayableTax * params.getCommanderRecastPenalty();
                }
            }
        }

        // DARRELLBEST-FORK: fold the four terms above into the buckets that are actually RETURNED.
        //
        // They were previously summed into a local `int score` that only ever reached logger.debug,
        // while the returned PlayerEvaluateScore was built from the six life/hand/permanent fields
        // alone. getTotalScore() is playerScore - opponentScore over exactly those six, so
        // stackObjectWeight, manaSourceValue and deployedManaValueWeight -- all three tuned, and one
        // of them tuned against a measured behaviour change -- have never influenced a single
        // decision. The evaluator computed them at every leaf and threw them away.
        //
        // Whatever tuning produced 150/60/40 was therefore measuring a no-op, and those magnitudes
        // have no empirical support. They need re-A/B-ing now that they execute.
        //
        // Folding into the existing buckets rather than widening the constructor keeps getTotalScore
        // correct by construction: it subtracts opponent from player, so an own-side quantity added
        // to the player side and an opponent-side quantity added to the opponent side both land with
        // the right sign, and an already-differential quantity (stackScore) goes on the player side.
        //
        // The three terms added later follow the same rule. Overextension and the commander recast
        // tax are costs WE pay, so they are subtracted from the player side; must-answer is value
        // sitting on the OPPONENT's board, so it is added to the opponent side and the bot recovers
        // it by removing the permanent that carries it.
        playerPermanentsScore += developmentScore + manaValueScore + unspentScore + stackScore
                - overextensionScore - commanderRecastScore;
        opponentPermanentsScore += opponentDevelopmentScore + opponentManaValueScore + mustAnswerScore;

        if (logger.isDebugEnabled()) {
            logger.debug((playerLifeScore - opponentLifeScore)
                    + (playerPermanentsScore - opponentPermanentsScore)
                    + (playerHandScore - opponentHandScore)
                    + " total Score (life:" + (playerLifeScore - opponentLifeScore)
                    + " permanents:" + (playerPermanentsScore - opponentPermanentsScore)
                    + " hand:" + (playerHandScore - opponentHandScore) + ')');
        }
        return new PlayerEvaluateScore(
                playerId,
                playerLifeScore, playerHandScore, playerPermanentsScore,
                opponentLifeScore, opponentHandScore, opponentPermanentsScore);
    }

    /**
     * DARRELLBEST-FORK: choose which opponent this evaluation scores against.
     * <p>
     * Mode 0 reproduces upstream exactly — the first opponent the engine happens to return. Mode 1
     * picks the most threatening one by the same measure the evaluator itself uses (life + hand +
     * board), so in a multiplayer game the bot reacts to whoever is actually about to win rather
     * than to an arbitrary seat.
     * <p>
     * Still a single opponent by design, not a sum over all of them: every downstream term
     * (life difference, permanent difference, hand difference) keeps its existing meaning and
     * scale, so this is a targeting fix rather than a rescaling of the whole evaluator. Summing
     * would change what every other weight means and invalidate the tuning done against them.
     */
    /**
     * DARRELLBEST-FORK: does this permanent tap for mana?
     * <p>
     * Extracted so the mana-source count is written once rather than duplicated in two adjacent
     * loops, and hot enough to justify a plain loop over the stream it replaces: this runs per
     * permanent, per side, at every search leaf.
     */
    private static boolean hasManaAbility(Permanent permanent, Game game) {
        for (mage.abilities.Ability ability : permanent.getAbilities(game)) {
            if (ability instanceof mage.abilities.mana.ManaAbility) {
                return true;
            }
        }
        return false;
    }

    private static Player selectOpponent(UUID playerId, Game game, boolean useCombatPermanentScore,
            CommanderEvalParams params) {
        java.util.Set<UUID> opponentIds = game.getOpponents(playerId, false);
        if (params.getOpponentSelectionMode() == 0 || opponentIds.size() <= 1) {
            return game.getPlayer(opponentIds.stream().findFirst().orElse(null));
        }
        Player worst = null;
        int worstScore = Integer.MIN_VALUE;
        for (UUID opponentId : opponentIds) {
            Player candidate = game.getPlayer(opponentId);
            if (candidate == null) {
                continue;
            }
            int threat = ArtificialScoringSystem.getLifeScore(candidate.getLife(), params)
                    + candidate.getHand().size() * params.getHandCardScore();
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(opponentId)) {
                threat += evaluatePermanent(permanent, game, useCombatPermanentScore, params);
            }
            if (threat > worstScore) {
                worstScore = threat;
                worst = candidate;
            }
        }
        return worst;
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
     * <p>
     * DARRELLBEST-FORK: the watcher is looked up by the commander's <b>main card</b> id, never by
     * the id of the object this loop happened to find.
     * <p>
     * {@code CommanderInfoWatcher} is registered exactly ONCE per commander, keyed on the MAIN card
     * id: {@code GameCommanderImpl.initCommander} iterates {@code getCommandersIds(..., false)} and
     * passes {@code commander.getId()} (GameCommanderImpl.java:125-154), and the key format is
     * {@code sourceId.toString() + "CommanderInfoWatcher"} (Watcher.getKey). But the object this
     * loop finds is NOT always that card. For a double-faced commander on the BATTLEFIELD,
     * {@code getCommanderCardsFromAnyZones} resolves the battlefield through
     * {@code getPermanent(id)} over <i>all card parts</i> ({@code getCommandersIds(..., true)} ->
     * CardUtil.getObjectParts, Game.java:729-795), and the only hit is the permanent for the face
     * that is UP -- carrying the HALF's id. The main card is not in the returned set at all: it is
     * not in the command zone or the graveyard, so no other branch adds it.
     * <p>
     * So the old {@code commander.getId()} lookup could only miss: {@code Watchers.get} logged
     * "not found in watchers" (Watchers.java:51) and returned null, and the null guard below turned
     * that into a silent 0. <b>A double-faced commander's damage was invisible to this penalty for
     * exactly as long as that commander was on the battlefield</b> -- which is when it is dealing
     * the damage, and the only time the penalty matters.
     * <p>
     * Measured, bench, reproducing matchup (Saryth vs PeterParker, seed 606): 6,054 watcher misses
     * in one game, and the only two keys that ever missed were the two halves of the one MDFC
     * commander, Peter Parker // Amazing Spider-Man. Instrumented at this call site, 12 of 12
     * sampled invocations had the returned set equal to exactly
     * {@code [PermanentCard/Amazing Spider-Man]} with the main card absent, hiding a real 4
     * commander damage (penalty 0 instead of 8000*4*4/441 = 290). Single-faced commanders (Torbran,
     * Krenko) have one part whose id IS the main card id, which is why the bug looked
     * deck-dependent rather than universal.
     * <p>
     * {@code getMainCard()} is the engine's own idiom here and is what the watcher itself matches
     * damage on ({@code sourceId.equals(commander.getMainCard().getId())},
     * CommanderInfoWatcher.watch) -- this makes the reader agree with the writer. Read-side fix
     * only: registration is untouched, so nothing on the live-server path can double-register. On a
     * single-faced commander {@code getMainCard()} returns {@code this} (CardImpl) and the lookup is
     * identical to before.
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
                mage.cards.Card mainCard = commander.getMainCard();
                mage.watchers.common.CommanderInfoWatcher watcher = game.getState()
                        .getWatcher(mage.watchers.common.CommanderInfoWatcher.class,
                                mainCard == null ? commander.getId() : mainCard.getId());
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
