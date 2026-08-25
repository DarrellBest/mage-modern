package mage.player.ai.commander.score;

import mage.player.ai.score.MagicAbility;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.abilities.keyword.HasteAbility;
import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.UUID;

/**
 * @author ubeefx, nantuko
 * <p>
 * DARRELLBEST-FORK: the weights this class used to hold as {@code private static final} constants now
 * live in {@link CommanderEvalParams}, which every scoring method takes as a required argument.
 * <p>
 * Required, not defaulted: there is deliberately NO overload that supplies
 * {@link CommanderEvalParams#DEFAULT} for you. A convenience overload would make "forgot to thread
 * the params through" compile and run, producing a bot that accepts a tuned config and quietly scores
 * with the stock weights -- exactly the failure this refactor exists to make impossible. The compiler
 * is the only thing that reliably catches a dropped parameter, so it is given the chance to.
 * <p>
 * {@link #WIN_GAME_SCORE}/{@link #LOSE_GAME_SCORE} stay {@code static final}: they are sentinels
 * compared for exact equality by the search, not weights (see {@link CommanderEvalParams}).
 */
public final class ArtificialScoringSystem {

    public static final int WIN_GAME_SCORE = 100000000;
    public static final int LOSE_GAME_SCORE = -WIN_GAME_SCORE;

    private static final int UNKNOWN_CARD_SCORE = 300;

    public static int getCardDefinitionScore(final Game game, final Card card, final CommanderEvalParams params) {
        int value = params.getBaseCardValue(); //TODO: add new rating system card value
        if (card.isLand(game)) {
            int score = (int) ((value / 2.0f) * params.getLandBaseMultiplier());
            //TODO: check this for "any color" lands
            //TODO: check this for dual and filter lands
            /*for (Mana mana : card.getMana()) {
             score += 50;
             }*/
            score += card.getMana().size() * params.getLandPerManaSymbol();
            return score;
        }

        final int score = value * params.getNonLandBaseMultiplier()
                - card.getManaCost().manaValue() * params.getManaValuePenaltyPerPip();
        if (card.getCardType(game).contains(CardType.CREATURE)) {
            return score + (card.getPower().getValue() + card.getToughness().getValue())
                    * params.getCardPowerToughnessMultiplier();
        } else {
            return score + (/*card.getRemoval()*50*/+(card.getRarity() == null
                    ? 0
                    : card.getRarity().getRating() * params.getRarityMultiplier()));
        }
    }

    public static int getFixedPermanentScore(final Game game, final Permanent permanent, final CommanderEvalParams params) {
        //TODO: cache it inside Card
        int score = getCardDefinitionScore(game, permanent, params);
        score += params.getPermanentOnBattlefieldBonus();
        if (permanent.getCardType(game).contains(CardType.CREATURE)) {
            // TODO: implement in the mage core
            //score + =cardDefinition.getActivations().size()*50;
            //score += cardDefinition.getManaActivations().size()*80;
        } else {
            if (permanent.hasSubtype(SubType.EQUIPMENT, game)) {
                score += params.getEquipmentPermanentBonus();
            }
        }
        return score;
    }

    /**
     * DARRELLBEST-FORK: does this permanent generate cards?
     * <p>
     * Detected by effect type rather than a card list, so it covers Rhystic Study, Mystic Remora,
     * Guardian Project, Esper Sentinel, Skullclamp and anything printed later, with no maintenance.
     * <p>
     * Deliberately counts any draw effect on the permanent, including enters-the-battlefield draws.
     * Distinguishing "repeatable" from "one-shot" reliably would mean interpreting trigger
     * conditions, and over-valuing a Mulldrifter body slightly is a far smaller error than the one
     * being fixed -- which is valuing a Rhystic Study exactly like a vanilla trinket.
     */
    private static boolean isDrawEngine(final Game game, final Permanent permanent) {
        for (Ability ability : permanent.getAbilities(game)) {
            for (Effect effect : ability.getAllEffects()) {
                if (isDrawEffectClass(effect.getClass())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * DARRELLBEST-FORK: memoises "does this Effect class draw cards" by CLASS.
     * <p>
     * The test is {@code getClass().getSimpleName().contains("DrawCard")}, and it ran once per
     * effect, per ability, per permanent, at every search leaf. On the live server's jre1.8.0_201
     * {@code Class.getSimpleName()} is not cached -- that arrived in a much later JDK -- so it
     * substrings the binary name and allocates a String EVERY time. The answer depends only on the
     * class, so it is computed once per class ever and read from a hash map afterwards.
     * <p>
     * This is the same memo, for the same reason, as {@code StateFeatures.DRAW_EFFECT_BY_CLASS};
     * they are separate maps only because the two classes are in different packages and neither
     * should have to depend on the other's internals to score a permanent.
     * <p>
     * Shared across games, hence concurrent: the live server plays many games in one JVM, and the
     * search runs a simulation pool. Writes race harmlessly -- two threads computing the same key
     * compute the same value.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean> DRAW_EFFECT_BY_CLASS
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isDrawEffectClass(final Class<?> effectClass) {
        Boolean known = DRAW_EFFECT_BY_CLASS.get(effectClass);
        if (known == null) {
            known = effectClass.getSimpleName().contains("DrawCard");
            DRAW_EFFECT_BY_CLASS.put(effectClass, known);
        }
        return known;
    }

    /**
     * DARRELLBEST-FORK: the largest power {@link #getOutclassThreshold} will ever return.
     * <p>
     * It exists so that {@code StateFeatures} can answer "how many of their creatures are at or
     * above the threshold" from a fixed-size bucket count taken during its single battlefield pass,
     * instead of walking the board a second time once the threshold is known. Both sides clamp with
     * this same constant, so the evaluator and the learner compute the SAME quantity -- which is the
     * whole point of the feature/term correspondence. Changing it changes both together.
     */
    public static final int MUST_ANSWER_MAX_POWER = 16;

    /**
     * DARRELLBEST-FORK: how many creatures ahead of the opponent's board the bot may be before
     * {@link CommanderEvalParams#getOverextensionPenalty()} starts applying.
     * <p>
     * A CONSTANT and not a tunable, deliberately. {@code StateFeatures.extract} has no
     * {@link CommanderEvalParams} -- a feature is a pure function of the game state, and the params
     * enter only through the seed -- so a quantity defined in terms of a tunable could not be
     * mirrored by the learner at all: the evaluator and the feature would compute different numbers
     * the moment anyone tuned it, and the seed would silently stop describing the evaluator. Making
     * it structural is what keeps the two definitions the same definition. Same reasoning as
     * {@link #MUST_ANSWER_MAX_POWER}.
     * <p>
     * 3 is "enough board to win through a blocker or two": at parity plus three creatures, the next
     * one adds little to the attack and mostly adds to what a sweeper collects.
     */
    public static final int OVEREXTENSION_MARGIN = 3;

    /**
     * DARRELLBEST-FORK: the extra generic mana a commander costs per previous cast from the command
     * zone (rule 903.8). A rule of the game rather than a weight, and shared with
     * {@code StateFeatures} for the same reason as {@link #OVEREXTENSION_MARGIN}.
     */
    public static final int COMMANDER_TAX_PER_CAST = 2;

    /**
     * DARRELLBEST-FORK: the power at which an opponent's creature counts as outclassing this
     * player's board -- twice the average power of the creatures the player controls.
     * <p>
     * "Twice my average" rather than an absolute number because the same 4/4 is a must-answer threat
     * against a board of 1/1 tokens and an ordinary creature against a board of 5/5s. Integer
     * arithmetic throughout so the evaluator and {@code StateFeatures} cannot disagree by a rounding
     * step.
     * <p>
     * The floor of 2 is load-bearing. With no creatures the average is 0, and without the floor
     * every creature -- including a Llanowar Elves -- would read as outclassing an empty board and
     * be flagged as must-answer. That is precisely the mana-dork false positive principle 7 warns
     * about, so the floor buys it off for the cost of one comparison.
     * <p>
     * <b>Known artifact, stated rather than hidden: an average is not monotone in our own board.</b>
     * Adding a small creature LOWERS our average and therefore lowers the threshold, which can push
     * an opponent's creature over it and cost us one signal -- so this term places a small tax on
     * deploying a 1/1 next to a 6/6. It is bounded (one bonus per opponent creature that crosses)
     * and an order of magnitude below what deploying a creature is worth, so it shaves a decision
     * rather than reversing it; it is also part of why the weight is deliberately conservative.
     * The monotone alternative is "power greater than our BEST creature's", which cannot move when
     * we add a creature but fires on things a gang-block answers for free. Neither has been
     * measured. If this term goes to an A/B, run that variant as the second arm.
     */
    public static int getOutclassThreshold(final int myCreatureCount, final int myTotalPower) {
        int average = myCreatureCount <= 0 ? 0 : myTotalPower / myCreatureCount;
        int threshold = 2 * average;
        if (threshold < 2) {
            threshold = 2;
        }
        return Math.min(threshold, MUST_ANSWER_MAX_POWER);
    }

    /**
     * DARRELLBEST-FORK: is this ability an ALTAR -- an activated mana ability paid for by sacrificing
     * something?
     * <p>
     * This is the "engine" signal that is not a draw engine: Ashnod's Altar, Phyrexian Altar and the
     * free sacrifice outlets that turn a board into mana are the enablers most combo lines are built
     * on, and the evaluator sees them as ordinary two-mana artifacts.
     * <p>
     * <b>Narrower than "an activated ability with a tap or sacrifice cost that makes mana", on
     * purpose.</b> That description also matches every land on the battlefield and every mana dork,
     * and flagging those as must-answer targets would teach the bot to point removal at exactly what
     * principle 7 says never to point it at. Requiring a SACRIFICE cost keeps the altars and drops
     * the entire ramp package, which is the distinction that matters. Abilities that make CARDS need
     * no clause here at all -- {@code isDrawEngine} already covers them whatever their cost is.
     * <p>
     * Public so that {@code StateFeatures} can ask the same question inside the ability walk it
     * already makes, rather than re-deriving the definition and drifting from it.
     */
    public static boolean isSacrificeManaEngine(final Ability ability) {
        if (!(ability instanceof mage.abilities.mana.ManaAbility)
                || !(ability instanceof mage.abilities.ActivatedAbility)) {
            return false;
        }
        mage.abilities.costs.Costs<mage.abilities.costs.Cost> costs = ability.getCosts();
        for (int i = 0, n = costs.size(); i < n; i++) {
            if (costs.get(i) instanceof mage.abilities.costs.SacrificeCost) {
                return true;
            }
        }
        return false;
    }

    /**
     * DARRELLBEST-FORK: how many MUST-ANSWER signals this permanent shows -- the quantity behind
     * {@link CommanderEvalParams#getMustAnswerBonus()}.
     * <p>
     * The evaluator's threat model is power, toughness and a keyword table, so a Rhystic Study and a
     * vanilla 2/2 enchantment-sized permanent are indistinguishable to it, and the search had no
     * reason to prefer killing the one that wins the game. Principles 6, 7 and 10 are all about that
     * distinction and none of them can be expressed by moving a stat weight.
     * <p>
     * Every signal is read off RULES OBJECTS -- effect classes, card types, cost types, power -- and
     * never off card names or rendered rule text. Same philosophy as {@code isDrawEngine}, same
     * reason: a curated list of "the scary cards" is incomplete the day it is written, needs
     * maintaining forever, and is silently wrong for every set printed after it.
     * <p>
     * The signals, each worth one:
     * <ol>
     *   <li><b>Draw engine.</b> It keeps producing cards -- the single most common way a Commander
     *       game is decided by a permanent nobody killed.</li>
     *   <li><b>Planeswalker.</b> Must-answer by construction: it arrives, ticks up, and generates
     *       value every turn it is ignored. The type alone is the signal, no text needed.</li>
     *   <li><b>Altar.</b> {@link #isSacrificeManaEngine} -- a sacrifice outlet that makes mana, the
     *       shape most combo lines are assembled around.</li>
     *   <li><b>Outclasses the board.</b> Power at or above {@link #getOutclassThreshold}, i.e. twice
     *       the average power of the creatures we control. Something our board cannot block
     *       profitably has to be answered with a card instead.</li>
     * </ol>
     * A fifth, board-level signal -- "this board is lethal against us" -- cannot be judged from a
     * single permanent and lives in {@code GameStateEvaluator2}, where the total is known.
     * <p>
     * Signals are COUNTED rather than maxed, so a planeswalker that also draws cards outranks one
     * that does not, and a huge creature that is also an altar outranks a merely huge one. They are
     * deliberately not weighted individually: five weights would be five unmeasured numbers instead
     * of one, and there is no evidence to set any of them apart yet.
     * <p>
     * Cost: one walk of this permanent's abilities. The caller gates the whole thing on the weight
     * being non-zero, and it runs only for the opponent's permanents, never for our own.
     */
    public static int getMustAnswerSignals(final Game game, final Permanent permanent,
            final int outclassThreshold) {
        int signals = 0;
        boolean drawEngine = false;
        boolean altar = false;
        for (Ability ability : permanent.getAbilities(game)) {
            if (!drawEngine) {
                // The same test isDrawEngine makes, in its allocation-free form: getAllEffects()
                // builds and returns a fresh Effects list per ability, and this runs per opponent
                // permanent at every search leaf. Iterating the modes reaches exactly the same
                // effects in the same order -- getAllEffects() IS that loop plus a collector
                // (AbilityImpl.getAllEffects, Mage/abilities/AbilityImpl.java:979).
                for (mage.abilities.Mode mode : ability.getModes().values()) {
                    mage.abilities.effects.Effects effects = mode.getEffects();
                    for (int i = 0, n = effects.size(); i < n; i++) {
                        if (isDrawEffectClass(effects.get(i).getClass())) {
                            drawEngine = true;
                            break;
                        }
                    }
                    if (drawEngine) {
                        break;
                    }
                }
            }
            if (!altar && isSacrificeManaEngine(ability)) {
                altar = true;
            }
            if (drawEngine && altar) {
                break;
            }
        }
        if (drawEngine) {
            signals++;
        }
        if (altar) {
            signals++;
        }
        if (permanent.getCardType(game).contains(CardType.PLANESWALKER)) {
            signals++;
        }
        if (permanent.getCardType(game).contains(CardType.CREATURE)
                && permanent.getPower().getValue() >= outclassThreshold) {
            signals++;
        }
        return signals;
    }

    public static int getDynamicPermanentScore(final Game game, final Permanent permanent, final CommanderEvalParams params) {

        int score = permanent.getCounters(game).getCount(CounterType.CHARGE) * params.getChargeCounterScore();
        // DARRELLBEST-FORK: your own commander is worth more than its stats -- it is usually the
        // deck's engine and win condition, and it costs 2 more mana on every recast.
        if (params.getCommanderPermanentBonus() != 0) {
            mage.players.Player owner = game.getPlayer(permanent.getControllerId());
            if (owner != null && game.isCommanderObject(owner, permanent)) {
                score += params.getCommanderPermanentBonus();
            }
        }
        // DARRELLBEST-FORK: a permanent that keeps producing cards is worth more than its stats
        if (params.getDrawEngineBonus() != 0 && isDrawEngine(game, permanent)) {
            score += params.getDrawEngineBonus();
        }
        score += permanent.getCounters(game).getCount(CounterType.LEVEL) * params.getLevelCounterScore();
        score -= permanent.getDamage() * params.getDamageMarkedPenalty();
        if (permanent.getCardType(game).contains(CardType.CREATURE)) {
            final int power = permanent.getPower().getValue();
            final int toughness = permanent.getToughness().getValue();
            int abilityScore = 0;
            for (Ability ability : permanent.getAbilities(game)) {
                abilityScore += MagicAbility.getAbilityScore(ability);
            }
            score += power * params.getCreaturePowerMultiplier()
                    + getPositive(toughness) * params.getCreatureToughnessMultiplier()
                    + abilityScore * (getPositive(power) + params.getAbilityScorePowerOffset())
                    / params.getAbilityScoreDivisor();
            int enchantments = 0;
            int equipments = 0;
            for (UUID uuid : permanent.getAttachments()) {
                MageObject object = game.getObject(uuid);
                if (object instanceof Card) {
                    Card card = (Card) object;
                    // TODO: implement getOutcomeTotal for permanents and cards too (not only attachments)
                    int outcomeScore = card.getAbilities(game).getOutcomeTotal();
                    if (card.getCardType(game).contains(CardType.ENCHANTMENT)) {
                        enchantments = enchantments + outcomeScore * params.getAttachedEnchantmentOutcomeMultiplier();
                    } else {
                        equipments = equipments + outcomeScore * params.getAttachedEquipmentOutcomeMultiplier();
                    }
                }
            }
            score += equipments + enchantments;
        }
        return score;
    }

    public static int getCombatPermanentScore(final Game game, final Permanent permanent, final CommanderEvalParams params) {
        int score = 0;
        if (!canTap(game, permanent)) {
            score += getTappedScore(game, permanent, params);
        }
        if (permanent.getCardType(game).contains(CardType.CREATURE)) {
            if (!permanent.canAttack(null, game)) {
                score += params.getCannotAttackPenalty();
            }
            if (!permanent.canBlockAny(game)) {
                score += params.getCannotBlockPenalty();
            }
        }
        return score;
    }

    private static boolean canTap(Game game, Permanent permanent) {
        return !permanent.isTapped()
                && (!permanent.hasSummoningSickness()
                || !permanent.getCardType(game).contains(CardType.CREATURE)
                || permanent.getAbilities(game).contains(HasteAbility.getInstance()));
    }

    private static int getPositive(int value) {
        return Math.max(0, value);
    }

    public static int getTappedScore(Game game, final Permanent permanent, final CommanderEvalParams params) {
        if (permanent.isCreature(game)) {
            return params.getTappedCreaturePenalty();
        } else if (permanent.isLand(game)) {
            return params.getTappedLandPenalty(); // means probably no mana available  (should be greater than passivity penalty
        } else {
            return params.getTappedOtherPenalty();
        }
    }

    public static int getLifeScore(final int life, final CommanderEvalParams params) {
        final int maxLife = params.getMaxTabulatedLife();
        if (life > maxLife) {
            return params.getLifeScoreAt(maxLife) + (life - maxLife) * params.getLifeAboveMultiplier();
        } else if (life >= 0) {
            return params.getLifeScoreAt(life);
        } else {
            return 0;
        }
    }

    public static int getManaScore(final int amount) {
        return -amount;
    }

    public static int getAttackerScore(final Permanent attacker) {
        //TODO: implement this
        /*int score = attacker.getPower().getValue() * 5 + attacker.lethalDamage * 2 - attacker.candidateBlockers.length;
         for (final MagicCombatCreature blocker : attacker.candidateBlockers) {

         score -= blocker.power;
         }
         // Dedicated attacker.
         if (attacker.hasAbility(MagicAbility.AttacksEachTurnIfAble) || attacker.hasAbility(MagicAbility.CannotBlock)) {
         score += 10;
         }
         // Abilities for attacking.
         if (attacker.hasAbility(MagicAbility.Trample) || attacker.hasAbility(MagicAbility.Vigilance)) {
         score += 8;
         }
         // Dangerous to block.
         if (!attacker.normalDamage || attacker.hasAbility(MagicAbility.FirstStrike) || attacker.hasAbility(MagicAbility.Indestructible)) {
         score += 7;
         }
         */
        int score = 0;
        return score;
    }
}
