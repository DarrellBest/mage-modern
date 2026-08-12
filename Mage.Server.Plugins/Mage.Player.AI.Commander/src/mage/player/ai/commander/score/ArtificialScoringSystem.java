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
                String name = effect.getClass().getSimpleName();
                if (name.contains("DrawCard")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int getDynamicPermanentScore(final Game game, final Permanent permanent, final CommanderEvalParams params) {

        int score = permanent.getCounters(game).getCount(CounterType.CHARGE) * params.getChargeCounterScore();
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
