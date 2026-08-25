package mage.player.ai.commander.learn;

import mage.abilities.Abilities;
import mage.abilities.Ability;
import mage.abilities.EvasionAbility;
import mage.abilities.Mode;
import mage.abilities.effects.Effects;
import mage.abilities.keyword.MenaceAbility;
import mage.abilities.keyword.SkulkAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.abilities.mana.ManaAbility;
import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.CommanderCardType;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.player.ai.commander.score.ArtificialScoringSystem;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.players.Player;
import mage.watchers.common.CommanderInfoWatcher;
import mage.watchers.common.CommanderPlaysCountWatcher;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * DARRELLBEST-FORK: turns a game state into a fixed-length vector of numbers, so a model can be
 * trained to score positions instead of the score coming from hand-tuned constants.
 * <p>
 * Most features are a DIFFERENTIAL (mine minus the opponents' average), for two reasons. It halves
 * the parameter count, and more importantly it builds in the symmetry the game actually has: a
 * position is good for me exactly as much as its mirror is good for my opponent. A model trained on
 * absolute counts has to learn that symmetry from data, and with limited data it learns it
 * imperfectly and plays asymmetrically.
 * <p>
 * A few features are deliberately ABSOLUTE, because the differential provably cannot express them.
 * {@code (40, 40)} and {@code (3, 3)} have the same life differential and are completely different
 * games -- one is turn three, the other is one attack from over. Everything absolute is named
 * without a {@code _diff} suffix.
 * <p>
 * <b>Only information the scored player itself has.</b> This used to be stated as "only public
 * information", and the hand-quality features at the end of the table are why it needed restating
 * rather than quietly breaking. What makes a feature illegitimate is not that it is hidden, it is
 * that it is hidden FROM THE PLAYER BEING SCORED. The AI evaluates simulated states where the engine
 * may well permit peeking, and a model trained on something it can see in simulation but not in a
 * real game learns to play like a cheater and then plays badly once the information is gone; that is
 * a correctness requirement, not an ethics one. An opponent's hand contents and everybody's library
 * order are hidden from this player and are still never read. This player's OWN hand is not hidden
 * from it -- the real player is holding those cards at the moment it decides -- so reading it in
 * simulation costs nothing when the same model plays a real game. {@link #extract} takes exactly one
 * playerId and reads only that player's hand, including inside {@code ComputerPlayer6}'s per-seat
 * snapshot, where each emitted row therefore describes what that seat itself knew.
 * <p>
 * Feature scaling is deliberately crude (raw counts, not normalised). Logistic regression handles
 * unscaled inputs by learning small coefficients; keeping the raw values makes the learned weights
 * directly comparable to the hand-tuned constants they replace, which is what makes the first
 * trained model auditable ("it decided life is worth 12 cards, not 60").
 * <p>
 * <b>{@link #extract} runs at every minimax leaf.</b> Every line in it is on the hot path, which is
 * why it makes exactly one pass over the battlefield, one over each permanent's abilities, one over
 * the stack and one over this player's hand -- four walks total, however many features are derived
 * from them. Nothing here may allocate or re-query per feature; see the comments inside for what
 * that costs. A feature whose quantity cannot be folded into one of those four walks does not go in
 * the table, and the ones rejected on that ground are named in the comments where they would have
 * gone, so the next person does not have to rediscover the cost.
 *
 * @author Darrell Best
 */
public final class StateFeatures {

    /**
     * 21 combat damage from a SINGLE commander is lethal (rule 903.14a). Used both to clamp the
     * commander-damage features and to convert {@code commanderDamageWeight} -- which the evaluator
     * prices as "what dying to commander damage is worth" -- into a per-point coefficient.
     */
    private static final int COMMANDER_DAMAGE_LETHAL = 21;

    /**
     * DARRELLBEST-FORK: one feature's full definition -- name, normaliser and hand-tuned seed --
     * held together so they cannot drift apart.
     * <p>
     * They already did, once. {@code NAMES} lived here and {@code SCALE} lived in
     * {@link OnlineTDLearner}, as two arrays that had to stay index-aligned by convention alone.
     * Three features were appended to one and not the other; {@code logit()} threw
     * ArrayIndexOutOfBoundsException on the first evaluation of every game, the surrounding code
     * swallowed it, and three games "completed" having produced no learning at all. A length
     * assertion was added afterwards, which catches the crash but not the cause. Making a feature a
     * single object removes the possibility instead of reporting it.
     */
    private static final class Feature {

        final String name;

        /**
         * Rough magnitude of this feature, used ONLY to normalise the online gradient (see
         * {@link OnlineTDLearner}). Eyeballed ranges for Commander, not measured statistics; within
         * a factor of two or three is all that is needed to keep SGD stable, which is all this is
         * for. It does NOT affect what the model computes: {@link #seedFromParams} multiplies the
         * scale back in, so the effective coefficient on the raw feature value is independent of it.
         */
        final double scale;

        /**
         * The hand-tuned evaluator parameter this feature corresponds to, or null when there is no
         * honest correspondence and the feature should seed to 0 and be learned from scratch.
         */
        final ToIntFunction<CommanderEvalParams> seed;

        Feature(String name, double scale, ToIntFunction<CommanderEvalParams> seed) {
            this.name = name;
            this.scale = scale;
            this.seed = seed;
        }
    }

    /**
     * The feature table. Its ORDER IS A WIRE FORMAT: {@link FederatedWeights} writes weights keyed
     * by name but every archived model, the offline fitting tooling and
     * {@code ComputerPlayer6}'s feature dumps key off position. Appending is safe. Reordering or
     * repurposing an existing entry silently re-points every previously trained weight at the wrong
     * feature, which does not throw and does not look like a bug -- it looks like the model
     * regressing for no reason.
     * <p>
     * Indices 0..15 are the original vector; 16..25 were the first append. All 26 are frozen -- they
     * are the format every archived model on disk was trained in. 26 onwards were appended later
     * still, and are frozen the moment a model is trained with them.
     */
    private static final Feature[] FEATURES = {
            //           name                            scale  seed from the tuned evaluator
            new Feature("life_diff",                        40, CommanderEvalParams::getLifeAboveMultiplier),
            new Feature("hand_size_diff",                    7, CommanderEvalParams::getHandCardScore),
            new Feature("creature_count_diff",               8, CommanderEvalParams::getPermanentOnBattlefieldBonus),
            new Feature("creature_power_diff",              20, CommanderEvalParams::getCreaturePowerMultiplier),
            new Feature("creature_toughness_diff",          20, CommanderEvalParams::getCreatureToughnessMultiplier),
            new Feature("land_count_diff",                   8, CommanderEvalParams::getLandBaseMultiplier),
            new Feature("untapped_land_diff",                8, p -> -p.getTappedLandPenalty()),
            new Feature("artifact_count_diff",               6, CommanderEvalParams::getPermanentOnBattlefieldBonus),
            new Feature("enchantment_count_diff",            4, CommanderEvalParams::getPermanentOnBattlefieldBonus),
            new Feature("planeswalker_count_diff",           2, CommanderEvalParams::getPermanentOnBattlefieldBonus),
            new Feature("commander_on_battlefield_diff",     1, CommanderEvalParams::getCommanderPermanentBonus),
            new Feature("library_size_diff",                60, null),
            new Feature("turn_number",                      30, null),
            // deployed_mana_value_diff -- total mana value of permanents; a developed board is ~20-30
            new Feature("deployed_mana_value_diff",         25, CommanderEvalParams::getDeployedManaValueWeight),
            // unspent_mana_own_turn -- untapped sources left on the bot's own main phase. The
            // evaluator now VALUES those (manaSourceValue) rather than penalising them, so the seed
            // follows the sign of whichever term is active -- otherwise the learner would start out
            // believing the opposite of what the evaluator believes.
            new Feature("unspent_mana_own_turn",             4, p -> p.getManaSourceValue() - p.getUnspentManaPenalty()),
            // draw_engine_count_diff -- few permanents draw cards repeatedly
            new Feature("draw_engine_count_diff",            3, CommanderEvalParams::getDrawEngineBonus),

            // ---------------------------------------------------------------------------------
            // DARRELLBEST-FORK: appended. Everything above keeps its index and meaning forever.
            // ---------------------------------------------------------------------------------

            // Commander starts at 40 and the differential cannot tell (40,40) from (3,3). Seeds to 0
            // rather than to lifeAboveMultiplier: life_diff already carries my own life linearly, so
            // seeding here too would double-count it and the seed would no longer BE the evaluator.
            new Feature("my_life",                          40, null),
            // Who is closest to dying is the real threat signal in a pod; the average hides it. Also
            // seeds to 0 -- the evaluator expresses "which opponent matters" through
            // opponentSelectionMode, a mode switch, not a weight, so there is nothing honest to copy.
            new Feature("min_opp_life",                     40, null),
            // Scale 21 = lethal, so the raw feature reads directly as a fraction of the clock.
            // The evaluator prices the whole 21-damage clock at commanderDamageWeight, so a LINEAR
            // feature over 0..21 matches it at lethal with weight/21 per point. The evaluator's own
            // term is quadratic in damage taken; a single linear coefficient cannot be quadratic, so
            // this is the best-fitting line through it rather than a copy of it, and the curvature is
            // exactly the sort of thing the learner is there to recover.
            new Feature("commander_damage_out",             21, p -> p.getCommanderDamageWeight() / COMMANDER_DAMAGE_LETHAL),
            new Feature("commander_damage_in",              21, p -> -p.getCommanderDamageWeight() / COMMANDER_DAMAGE_LETHAL),
            // Evasive power is a subset of creature power (scale 20), and usually a minority of it.
            // No seed: the evaluator prices evasion through MagicAbility's per-keyword table divided
            // by abilityScoreDivisor, which is not a single parameter, so there is no honest scalar
            // to copy and inventing one would put a made-up number in the "this IS the tuned bot"
            // starting point.
            new Feature("evasive_power_diff",               10, null),
            // Tapped creatures are the ones not blocking. The evaluator charges exactly this, per
            // tapped creature, symmetrically on both sides -- an honest one-for-one correspondence.
            // Already negative (-100), so the sign carries over unchanged.
            new Feature("tapped_creature_diff",              4, CommanderEvalParams::getTappedCreaturePenalty),
            // Commander graveyards are a resource, not just a dump; 5-20 cards by mid game.
            new Feature("graveyard_size_diff",              20, null),
            // Already O(1) by construction (clamped to [0,2]), so its scale is 1.
            new Feature("lethality",                         1, null),
            new Feature("life_danger",                       1, null),
            // A commander recast three times is +6 mana of tax, which is most of a turn.
            new Feature("commander_cast_count",              3, null),

            // =================================================================================
            // DARRELLBEST-FORK: appended -- the nine tuned evaluator terms the learner was BLIND
            // to.
            //
            // {@link mage.player.ai.commander.score.EvalBreakdown} enumerates the 22 tunable
            // weights the heuristic evaluator actually multiplies something by. Nine of them had no
            // feature here at all, and a weight with nothing to multiply is not in the model: the
            // learner could not represent equipment, counters, marked damage, a creature that
            // cannot block, or anything on the stack, however it was seeded. That is not "the model
            // disagreeing with the tuned bot", which is the experiment this whole design exists to
            // run -- it is the model being unable to state the tuned bot's opinion in the first
            // place, so any measured difference between them was partly just missing vocabulary.
            //
            // Every quantity below is the UNWEIGHTED thing its parameter multiplies inside
            // ArtificialScoringSystem/GameStateEvaluator2, read off that code rather than inferred
            // from the parameter's name, so that feature * param reproduces the evaluator's own
            // contribution for that term. Where the code turned out to mean something other than
            // the name suggests, the comment says so -- those are the places a plausible guess
            // would have produced a seed that is confidently wrong.
            // =================================================================================

            // GameStateEvaluator2 prices a SPELL on the stack at its full card-definition score and
            // charges stackObjectWeight only for the objects that are NOT spells -- triggered and
            // activated abilities waiting to resolve. So the quantity behind this weight is the
            // ABILITY count, not the object count. Counting spells here would attach a 150-point
            // price to something the evaluator prices at 300-1000 and the seed would stop being a
            // copy of the evaluator. EvalBreakdown already calls this term "stack_objects", so the
            // name is kept and the definition follows the code; spells get their own feature below.
            // Typically 0-2 objects, hence scale 2.
            new Feature("stack_objects",                     2, CommanderEvalParams::getStackObjectWeight),
            // getFixedPermanentScore adds this in the NON-creature branch only, so an animated
            // Equipment stops counting the moment it becomes a creature. Matched exactly, including
            // that quirk. The subtype lookup is gated behind the artifact test the pass already
            // performs: rule 301.5 makes Equipment an artifact subtype, so the gate cannot exclude a
            // real Equipment, and it keeps a subtype lookup off the large majority of permanents.
            new Feature("equipment_permanents",              2, CommanderEvalParams::getEquipmentPermanentBonus),
            // Charge and level counters come off the SAME Counters map in one gated read -- that map
            // is empty for almost every permanent, so the common case costs one isEmpty(). Applied
            // to every permanent rather than to creatures, which is what getDynamicPermanentScore
            // does: Everflowing Chalice and Astral Cornucopia are artifacts, not creatures.
            new Feature("charge_counters",                   4, CommanderEvalParams::getChargeCounterScore),
            // Level counters are rare enough that 2 is already generous as a typical magnitude.
            new Feature("level_counters",                    2, CommanderEvalParams::getLevelCounterScore),
            // Damage marked on permanents, which getDynamicPermanentScore SUBTRACTS at
            // damageMarkedPenalty per point -- hence the negated seed, matching how
            // EvalBreakdown.weightsOf signs the same term. A positive differential means MY
            // creatures are the damaged ones, so the sign survives into the differential unchanged.
            // getDamage() is a plain field read, so this is free.
            new Feature("damage_marked",                     5, p -> -p.getDamageMarkedPenalty()),
            // permanent.canAttack(null, game), the exact predicate getCombatPermanentScore uses.
            //
            // Worth knowing what that predicate actually answers. canAttackInPrinciple asks
            // game.getCombat().getDefenders(), and Combat.clear() empties that set both at end of
            // combat and again at begin combat, so OUTSIDE the declare-attackers..end-of-combat
            // window there are no defenders at all, noneMatch over an empty set is true, and every
            // creature "cannot attack". For most leaves this feature is therefore just the creature
            // count and its differential collapses onto creature_count_diff; it only carries
            // independent information during combat. That is an accurate description of the
            // evaluator's own term, quirk included, and representing the evaluator faithfully is the
            // point -- if the quirk is a mistake, the learner can now learn its way off it, which it
            // could not do while the term was invisible to it.
            //
            // Cost is bounded by canAttack's own first line: it returns false for a tapped creature
            // before touching the engine, so only untapped creatures pay for a lookup.
            // Scale 8 to match creature_count_diff, which is what it mostly is.
            new Feature("cannot_attack",                     8, CommanderEvalParams::getCannotAttackPenalty),
            // permanent.canBlockAny(game), again exactly what getCombatPermanentScore asks. Unlike
            // cannot_attack this means something in every phase: it is Pacifism, "can't block"
            // restrictions, and being tapped. The same single call also produces
            // untapped_blockers_diff below, so the pair costs one engine query per creature, not two.
            new Feature("cannot_block",                      3, CommanderEvalParams::getCannotBlockPenalty),
            // Tapped permanents that are neither creature nor land -- getTappedScore's third branch,
            // reached through !canTap(). For a non-creature !canTap() is exactly isTapped(), because
            // the summoning-sickness half of canTap() is guarded by a creature check, so the plain
            // tapped test here is the evaluator's predicate and not an approximation of it. Free:
            // the pass already holds both the card types and the tapped flag.
            new Feature("tapped_other",                      3, CommanderEvalParams::getTappedOtherPenalty),
            // Counted the way evaluatePermanent counts it: once per detrimental EFFECT rather than
            // once per aura (there is no break in that loop), and only when the attachment's
            // controller is the enchanted permanent's controller -- which is what makes it "own"
            // auras rather than an opponent's Pacifism. Almost every permanent has no attachments,
            // and the whole block sits behind that isEmpty() test. Usually 0, so scale 1.
            new Feature("detrimental_own_auras",             1, CommanderEvalParams::getDetrimentalOwnAuraPenalty),

            // =================================================================================
            // DARRELLBEST-FORK: appended -- concepts absent from the evaluator AND from the
            // learner, which a linear model provably cannot build out of what is already here.
            //
            // These seed to 0 with two exceptions noted below. There is no tuned counterpart to
            // copy, so the model starts agnostic about them and either learns something or leaves
            // them near zero, which is itself a readable result.
            // =================================================================================

            // min(my creatures, their average). A count DIFFERENTIAL is 5 for both 5-vs-0 and
            // 10-vs-5, and those are not the same game: the first is an open board being raced, the
            // second is a stall where nothing attacks profitably. The minimum is the cheapest honest
            // statement of "how much mutual blocking there is", and NO linear combination of the two
            // counts can produce it, because min() is not linear -- which is exactly why it has to
            // be a feature rather than something the model could infer.
            new Feature("board_texture",                     5, null),
            // Creatures that could actually block next turn. Deliberately NOT
            // (creature_count - tapped_creature): that difference is a linear combination of two
            // features already in the vector, so a model given it would gain precisely nothing. What
            // makes this independent is the canBlockAny filter -- Pacifism, "can't block" and
            // similar restrictions are invisible to every other feature here. Reuses the engine call
            // cannot_block already makes.
            new Feature("untapped_blockers_diff",            5, null),
            // "Pressure their board cannot answer", as a CLOCK rather than a count. The exact
            // version -- match each of my evaders against each of their creatures for
            // flying/reach/protection -- is O(my creatures * their creatures) with an ability scan on
            // both sides at every leaf, and was rejected on that cost. This is the cheap shape of the
            // same idea, and the part that earns its place is that it is a THRESHOLD: a linear model
            // cannot build min(2, power/life) out of evasive_power_diff and min_opp_life separately.
            // Honest about what it is not: isEvasive() counts trample, which is blockable, so this
            // reads as "hard-to-block pressure against the shortest life total", not as guaranteed
            // damage. Clamped at 2 for the same reason lethality is.
            new Feature("evasive_lethality",                 1, null),
            // A planeswalker COUNT cannot tell a walker at 1 loyalty from one at 9, and loyalty is
            // simultaneously its life total and its remaining output -- the single most informative
            // number about a permanent type the vector already tracks. Read from the same Counters
            // map the counter features open, so it is one more lookup on an object already in hand,
            // and only for planeswalkers.
            new Feature("planeswalker_loyalty_diff",         6, null),
            // Creatures that are untapped but cannot do anything yet.
            //
            // Seeded from tappedCreaturePenalty, and that is a real correspondence rather than a
            // stand-in. ArtificialScoringSystem charges the tapped-creature penalty through
            // !canTap(), and for a creature canTap() reduces to "!isTapped() &&
            // !hasSummoningSickness()" -- the haste clause sitting beside it is dead code, because
            // hasSummoningSickness() already returns false when the permanent has haste. So the
            // evaluator's quantity is {tapped} UNION {untapped and summoning-sick}, and index 21 only
            // ever covered the first half of it. This is the other half at the same weight, which
            // makes the seeded model a strictly closer copy of the evaluator than it was before.
            new Feature("summoning_sick_diff",               2, CommanderEvalParams::getTappedCreaturePenalty),

            // ---------------------------------------------------------------------------------
            // Own-hand quality. See the class note on what "information the scored player has"
            // means and why this is the boundary of that rule rather than a breach of it.
            //
            // Hand SIZE is in the vector twice over (index 1) and says nothing about hand QUALITY,
            // which in Commander is most of what a hand is: seven lands and seven six-drops are the
            // same number today. One walk of the hand produces all four of these.
            // ---------------------------------------------------------------------------------

            // Lands in hand -- flood and screw. Together with land_count_diff this is the only way
            // the vector can distinguish "four lands out and three more in hand" from "four lands out
            // and nothing left to hit", which are different games by turn six.
            new Feature("hand_land_count",                   3, null),
            // Non-land cards whose mana value is at most my untapped mana source count. COLOUR-BLIND
            // on purpose: a true castability check means player.getManaAvailable(game), which
            // enumerates mana options across every source and is far too expensive to run at a search
            // leaf. Generic mana value against source count still catches the case that dominates in
            // practice -- a hand of cards simply too expensive to play yet.
            new Feature("hand_castable_now",                 3, null),
            // Average mana value of the NON-LAND cards in hand, 0 when there are none. Curve quality:
            // separates a hand of cheap interaction from a hand of uncastable bombs at identical hand
            // size. Lands are excluded rather than counted as 0 so that this measures the spells and
            // hand_land_count measures the lands, instead of both being a blur of the two.
            new Feature("hand_avg_mana_value",               3, null),
            // Instants and sorceries in hand whose effects are hostile -- destroy, exile, damage,
            // Outcome.Removal, or Outcome.Detriment (which is what CounterTargetEffect declares, so
            // counterspells are included). Whether the bot is holding an answer is a first-order
            // Commander concept and neither the evaluator nor the learner had any way to say it.
            //
            // Detected by Outcome, NOT by rule text, and memoised per card CLASS, so the ability walk
            // happens once per distinct card ever rather than once per leaf. Without that memo this
            // feature would be unaffordable; with it, it is one hash lookup per card in hand. Same
            // trick and same reasoning as DRAW_EFFECT_BY_CLASS.
            new Feature("interaction_held",                  2, null),

            // Spells, as opposed to abilities, waiting to resolve; mine minus theirs. Completes the
            // stack term that stack_objects only half-covers. Seeded to 0 deliberately: the evaluator
            // prices these at getCardDefinitionScore, which is five parameters in a trench (base card
            // value, land and non-land multipliers, a per-pip mana penalty, a rarity multiplier), not
            // a scalar. There is no single tuned number to copy, and inventing one would put a
            // made-up constant into the starting point whose entire claim is that it IS the tuned bot.
            new Feature("stack_spell_diff",                  1, null),

            // =================================================================================
            // DARRELLBEST-FORK: appended -- the three evaluator terms added with them, so the
            // learner can state the tuned bot's opinion about threat quality, overextension and
            // commander tax instead of being blind to all three.
            //
            // Each one is the UNWEIGHTED quantity its parameter multiplies inside
            // GameStateEvaluator2, and each seeds NEGATIVE, because all three are costs: two are
            // charged against our own side and the third is charged on the opponent's, which in a
            // differential evaluator is the same sign. Getting that sign wrong would seed the model
            // believing that opposing threats are good for us.
            // =================================================================================

            // Must-answer SIGNALS on the opponents' permanents, averaged over opponents like every
            // other opponent-side quantity here. Absolute rather than differential on purpose: the
            // evaluator charges this on the opponent's side only, so a mine-minus-theirs feature
            // would assert something the evaluator never says (that my own threats are worth extra
            // to me). min_opp_life is the existing precedent for an opponent-only feature.
            //
            // One difference from the evaluator, and it is the same difference every differential
            // feature here already has: the evaluator counts signals for the ONE opponent it scores
            // against, this averages over all live opponents. Identical in a duel; in a pod the
            // evaluator's number is the most-threatening seat's (opponentSelectionMode 1) and this
            // one is the table's. Typical values are 0-3, hence scale 3.
            new Feature("opp_must_answer",                   3, p -> -p.getMustAnswerBonus()),
            // Own creatures deployed past (their creatures + OVEREXTENSION_MARGIN), floored at 0.
            // The margin is a shared constant rather than a tunable precisely so this feature and
            // the evaluator term cannot drift apart -- see ArtificialScoringSystem.
            new Feature("overextension",                     2, p -> -p.getOverextensionPenalty()),
            // The part of the commander tax that cannot currently be paid, and 0 whenever our
            // commander is on the battlefield. commander_cast_count (index 25) is the raw count and
            // stays what it is; this is the count turned into the quantity the evaluator prices,
            // which a linear model cannot build from the count alone -- max(0, 2*casts - sources)
            // is a hinge, and it switches off entirely on a condition (commander on battlefield)
            // that is a different feature again.
            new Feature("commander_recast_tax",              4, p -> -p.getCommanderRecastPenalty()),

            // =================================================================================
            // DARRELLBEST-FORK: considered and REJECTED, recorded so the cost does not have to be
            // rediscovered by whoever asks for them next.
            //
            // COLOUR SCREW / true castability. The honest version is player.getManaAvailable(game),
            // which enumerates ManaOptions across every source and every alternative it can produce.
            // That is one of the most expensive calls in the engine and it would run at every leaf.
            // hand_castable_now is the colour-blind approximation kept in its place; a hand that is
            // all Islands holding a red spell still reads as castable, which is a real gap and a
            // deliberate one.
            //
            // EVASIVE POWER THEY CANNOT BLOCK, exactly. Requires pairing each of my evaders against
            // each of their creatures for flying, reach, protection and menace -- O(my creatures *
            // their creatures) with an ability scan on both sides, per leaf, on boards that already
            // make this bot log "AI player thinks too long". evasive_lethality is the O(1) stand-in.
            //
            // COMBO PROXIMITY. No cheap proxy was found and none is claimed. Every candidate that
            // was considered -- untapped-permanent loops, "cards that untap other permanents",
            // infinite-mana shapes -- needs either rule-text inspection or a curated card list, and
            // a curated list is exactly the maintenance burden isDrawEngine was written to avoid.
            // A wrong combo detector is worse than none: it would fire on ordinary boards and teach
            // the model that ordinary boards are about to win. Left out, honestly, rather than
            // approximated badly.
            //
            // OPPONENT HAND CONTENTS and library order. Not a cost question -- see the class note.
            // =================================================================================
    };

    /**
     * Feature names, in the exact order {@link #extract} emits them. DERIVED from {@link #FEATURES}
     * rather than stored, so it cannot disagree with the scales and seeds beside it.
     */
    public static final String[] NAMES = namesOf(FEATURES);

    public static final int SIZE = FEATURES.length;

    private static final double[] SCALES = scalesOf(FEATURES);

    // Index constants, looked up BY NAME so a constant can never end up pointing at a different
    // feature than the one it is named for. Costs one linear scan each, once, at class load.
    private static final int F_LIFE_DIFF = indexOf("life_diff");
    private static final int F_HAND_SIZE_DIFF = indexOf("hand_size_diff");
    private static final int F_CREATURE_COUNT_DIFF = indexOf("creature_count_diff");
    private static final int F_CREATURE_POWER_DIFF = indexOf("creature_power_diff");
    private static final int F_CREATURE_TOUGHNESS_DIFF = indexOf("creature_toughness_diff");
    private static final int F_LAND_COUNT_DIFF = indexOf("land_count_diff");
    private static final int F_UNTAPPED_LAND_DIFF = indexOf("untapped_land_diff");
    private static final int F_ARTIFACT_COUNT_DIFF = indexOf("artifact_count_diff");
    private static final int F_ENCHANTMENT_COUNT_DIFF = indexOf("enchantment_count_diff");
    private static final int F_PLANESWALKER_COUNT_DIFF = indexOf("planeswalker_count_diff");
    private static final int F_COMMANDER_ON_BATTLEFIELD_DIFF = indexOf("commander_on_battlefield_diff");
    private static final int F_LIBRARY_SIZE_DIFF = indexOf("library_size_diff");
    private static final int F_TURN_NUMBER = indexOf("turn_number");
    private static final int F_DEPLOYED_MANA_VALUE_DIFF = indexOf("deployed_mana_value_diff");
    private static final int F_UNSPENT_MANA_OWN_TURN = indexOf("unspent_mana_own_turn");
    private static final int F_DRAW_ENGINE_COUNT_DIFF = indexOf("draw_engine_count_diff");
    private static final int F_MY_LIFE = indexOf("my_life");
    private static final int F_MIN_OPP_LIFE = indexOf("min_opp_life");
    private static final int F_COMMANDER_DAMAGE_OUT = indexOf("commander_damage_out");
    private static final int F_COMMANDER_DAMAGE_IN = indexOf("commander_damage_in");
    private static final int F_EVASIVE_POWER_DIFF = indexOf("evasive_power_diff");
    private static final int F_TAPPED_CREATURE_DIFF = indexOf("tapped_creature_diff");
    private static final int F_GRAVEYARD_SIZE_DIFF = indexOf("graveyard_size_diff");
    private static final int F_LETHALITY = indexOf("lethality");
    private static final int F_LIFE_DANGER = indexOf("life_danger");
    private static final int F_COMMANDER_CAST_COUNT = indexOf("commander_cast_count");
    private static final int F_STACK_OBJECTS = indexOf("stack_objects");
    private static final int F_EQUIPMENT_PERMANENTS = indexOf("equipment_permanents");
    private static final int F_CHARGE_COUNTERS = indexOf("charge_counters");
    private static final int F_LEVEL_COUNTERS = indexOf("level_counters");
    private static final int F_DAMAGE_MARKED = indexOf("damage_marked");
    private static final int F_CANNOT_ATTACK = indexOf("cannot_attack");
    private static final int F_CANNOT_BLOCK = indexOf("cannot_block");
    private static final int F_TAPPED_OTHER = indexOf("tapped_other");
    private static final int F_DETRIMENTAL_OWN_AURAS = indexOf("detrimental_own_auras");
    private static final int F_BOARD_TEXTURE = indexOf("board_texture");
    private static final int F_UNTAPPED_BLOCKERS_DIFF = indexOf("untapped_blockers_diff");
    private static final int F_EVASIVE_LETHALITY = indexOf("evasive_lethality");
    private static final int F_PLANESWALKER_LOYALTY_DIFF = indexOf("planeswalker_loyalty_diff");
    private static final int F_SUMMONING_SICK_DIFF = indexOf("summoning_sick_diff");
    private static final int F_HAND_LAND_COUNT = indexOf("hand_land_count");
    private static final int F_HAND_CASTABLE_NOW = indexOf("hand_castable_now");
    private static final int F_HAND_AVG_MANA_VALUE = indexOf("hand_avg_mana_value");
    private static final int F_INTERACTION_HELD = indexOf("interaction_held");
    private static final int F_STACK_SPELL_DIFF = indexOf("stack_spell_diff");
    private static final int F_OPP_MUST_ANSWER = indexOf("opp_must_answer");
    private static final int F_OVEREXTENSION = indexOf("overextension");
    private static final int F_COMMANDER_RECAST_TAX = indexOf("commander_recast_tax");

    // Per-controller accumulators filled by the single battlefield pass. These are NOT feature
    // indices -- several features are computed from more than one of them.
    private static final int A_CREATURES = 0;
    private static final int A_POWER = 1;
    private static final int A_TOUGHNESS = 2;
    private static final int A_LANDS = 3;
    private static final int A_UNTAPPED_LANDS = 4;
    private static final int A_ARTIFACTS = 5;
    private static final int A_ENCHANTMENTS = 6;
    private static final int A_PLANESWALKERS = 7;
    private static final int A_COMMANDERS = 8;
    private static final int A_DEPLOYED_MANA_VALUE = 9;
    private static final int A_DRAW_ENGINES = 10;
    private static final int A_EVASIVE_POWER = 11;
    private static final int A_TAPPED_CREATURES = 12;
    private static final int A_EQUIPMENT = 13;
    private static final int A_CHARGE_COUNTERS = 14;
    private static final int A_LEVEL_COUNTERS = 15;
    private static final int A_LOYALTY = 16;
    private static final int A_DAMAGE_MARKED = 17;
    private static final int A_CANNOT_ATTACK = 18;
    private static final int A_CANNOT_BLOCK = 19;
    private static final int A_TAPPED_OTHER = 20;
    private static final int A_DETRIMENTAL_AURAS = 21;
    private static final int A_SUMMONING_SICK = 22;
    private static final int A_UNTAPPED_BLOCKERS = 23;
    /**
     * Must-answer signals, filled for OPPONENT seats only -- the evaluator charges the term on the
     * opponent's side and nowhere else, so counting it for our own board would cost an ability walk
     * per own permanent per leaf to produce a number nothing reads.
     */
    private static final int A_MUST_ANSWER = 24;
    private static final int A_COUNT = 25;

    /**
     * DARRELLBEST-FORK: memoises "does this Effect class draw cards" by CLASS.
     * <p>
     * The test itself is {@code getClass().getSimpleName().contains("DrawCard")}, which ran once per
     * effect per ability per permanent per leaf. On the live server's jre1.8.0_201
     * {@code Class.getSimpleName()} is not cached (that arrived in a much later JDK) -- it
     * substrings the binary name and allocates a String every single time. The answer depends only
     * on the class, so it is computed once per class ever and then read from a hash map.
     * <p>
     * Shared across games, hence concurrent: the live server plays many games in one JVM. Writes
     * race harmlessly because two threads computing the same key compute the same value.
     */
    private static final ConcurrentHashMap<Class<?>, Boolean> DRAW_EFFECT_BY_CLASS = new ConcurrentHashMap<>();

    /**
     * DARRELLBEST-FORK: memoises "is this card a piece of interaction" by CARD CLASS.
     * <p>
     * Every printed card in XMage is its own class, so the class IS the printed card -- which makes
     * it the right key for a question whose answer is a property of the printed card and nothing
     * else. The answer is computed once per distinct card the JVM ever sees and read from a hash map
     * afterwards, which is what makes {@code interaction_held} affordable at a search leaf at all:
     * without it, every card in hand would need its abilities and effects walked at every leaf.
     * <p>
     * Same concurrency argument as {@link #DRAW_EFFECT_BY_CLASS} -- shared across the many games one
     * server JVM plays, and two threads racing on the same key compute the same answer.
     */
    private static final ConcurrentHashMap<Class<?>, Boolean> INTERACTION_BY_CLASS = new ConcurrentHashMap<>();

    private StateFeatures() {
    }

    /**
     * Per-feature normalisers, in {@link #NAMES} order. {@link OnlineTDLearner} consumes this rather
     * than keeping its own copy -- that copy is precisely what drifted.
     *
     * @return a fresh array each call, so a caller cannot reach in and change the table
     */
    public static double[] scales() {
        return SCALES.clone();
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

        // DARRELLBEST-FORK: the EXCLUDING overload. The plain getOpponents(playerId) keeps returning
        // players who have already LOST until the end of the turn (its own javadoc says so, and
        // Game.java carries a TODO about call sites that get this wrong). A dead player was still
        // adding their life, hand, library and board into the opponent totals AND still counting
        // toward the divisor, so the turn someone died the bot saw every opponent average lurch for
        // reasons that had nothing to do with the position.
        Set<UUID> opponentIds = game.getOpponents(playerId, true);

        // Seat 0 is this player; seats 1..oppCount are the live opponents. Resolved once, up front,
        // because the battlefield pass below has to attribute every permanent to a seat and must not
        // re-query the engine to do it.
        int seatCapacity = opponentIds.size() + 1;
        Player[] seatPlayers = new Player[seatCapacity];
        UUID[] oppIds = new UUID[seatCapacity - 1];
        seatPlayers[0] = me;

        int oppCount = 0;
        int oppLife = 0;
        int oppHand = 0;
        int oppLibrary = 0;
        int oppGraveyard = 0;
        int minOppLife = Integer.MAX_VALUE;
        for (UUID oppId : opponentIds) {
            Player opp = game.getPlayer(oppId);
            if (opp == null) {
                continue;
            }
            oppIds[oppCount] = oppId;
            oppCount++;
            seatPlayers[oppCount] = opp;
            oppLife += opp.getLife();
            oppHand += opp.getHand().size();
            oppLibrary += opp.getLibrary().size();
            oppGraveyard += opp.getGraveyard().size();
            minOppLife = Math.min(minOppLife, opp.getLife());
        }
        if (oppCount == 0) {
            // Only reachable in a state the game is already over in, which the caller answers with
            // the hand-tuned evaluator's terminal score rather than the model. Neutral values here
            // just keep the vector well-formed.
            minOppLife = 0;
        }
        // Average rather than sum across opponents, so a feature means the same thing in a duel and
        // in a 4-player game. Summing would make "opponent life" three times larger in multiplayer
        // and a model trained mostly on duels would read every multiplayer board as catastrophic.
        double div = Math.max(oppCount, 1);

        int seatCount = oppCount + 1;
        @SuppressWarnings("unchecked")
        Set<UUID>[] seatCommanders = (Set<UUID>[]) new Set[seatCount];
        for (int seat = 0; seat < seatCount; seat++) {
            seatCommanders[seat] = commanderIdsOf(seatPlayers[seat], game);
        }

        PhaseStep step = game.getTurnStepType();
        boolean ownMain = playerId.equals(game.getActivePlayerId())
                && (step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN);

        // ------------------------------------------------------------------------------------
        // DARRELLBEST-FORK: ONE pass over the battlefield, and ONE pass over each permanent's
        // abilities.
        //
        // This used to be five passes in a duel and nine in a four-player pod -- the whole-board
        // scan, then getAllActivePermanents(playerId), then one per opponent, then the same again
        // inside the commander count. Every one of those is Battlefield.getAllActivePermanents,
        // which streams the entire field map and collects a fresh ArrayList, and all of it ran at
        // every minimax leaf. The per-permanent work was duplicated too: getCardType(game) was
        // called up to five times per permanent (isCreature, isLand and three contains checks, each
        // a state lookup), and getAllEffects() allocated a fresh list per ability.
        //
        // Everything below reads each permanent exactly once and writes into per-seat accumulators.
        // ------------------------------------------------------------------------------------
        int[] mine = new int[A_COUNT];
        int[] opps = new int[A_COUNT];
        int myUntappedSources = 0;
        // DARRELLBEST-FORK: opponent creature powers, bucketed.
        //
        // The must-answer term counts opponent creatures whose power clears a threshold, and that
        // threshold is twice OUR average creature power -- which is not known until this pass has
        // finished, because permanents arrive in whatever order the battlefield map yields. The
        // alternatives were a second walk of the battlefield (rejected: this method exists to make
        // exactly four walks) or keeping a per-creature list (rejected: an allocation per leaf that
        // grows with the board). A fixed 17-int histogram answers "how many are at or above t" for
        // any t after the fact, at one array write per opponent creature.
        //
        // The top bucket means "power >= MUST_ANSWER_MAX_POWER", and getOutclassThreshold clamps to
        // that same bound, so the count is exact for every threshold it can produce.
        int[] oppPowerBuckets = new int[ArtificialScoringSystem.MUST_ANSWER_MAX_POWER + 1];
        for (Permanent p : game.getBattlefield().getAllActivePermanents()) {
            int seat = seatOf(p.getControllerId(), playerId, oppIds, oppCount);
            if (seat < 0) {
                // Controlled by someone who is neither this player nor a live opponent: a player who
                // has lost but whose permanents have not been swept yet, or a player out of range.
                // They contribute to nothing. Counting their board while excluding them from the
                // divisor would make a dead player weigh MORE per head than a live one, which is the
                // opposite of the fix above.
                continue;
            }
            boolean isMine = seat == 0;
            int[] acc = isMine ? mine : opps;

            List<CardType> types = p.getCardType(game);
            boolean creature = types.contains(CardType.CREATURE);
            boolean land = types.contains(CardType.LAND);
            boolean artifact = types.contains(CardType.ARTIFACT);
            boolean planeswalker = types.contains(CardType.PLANESWALKER);
            boolean tapped = p.isTapped();

            int power = 0;
            if (creature) {
                power = Math.max(p.getPower().getValue(), 0);
                acc[A_CREATURES]++;
                acc[A_POWER] += power;
                if (!isMine) {
                    // Same clamp the evaluator applies, so both sides bucket the same creature the
                    // same way; see oppPowerBuckets above.
                    oppPowerBuckets[Math.min(power, ArtificialScoringSystem.MUST_ANSWER_MAX_POWER)]++;
                }
                acc[A_TOUGHNESS] += Math.max(p.getToughness().getValue(), 0);
                if (tapped) {
                    acc[A_TAPPED_CREATURES]++;
                } else if (p.hasSummoningSickness()) {
                    // The other half of the evaluator's tapped-creature quantity; see
                    // summoning_sick_diff in the table. hasSummoningSickness() already accounts for
                    // haste, so no ability check is needed here.
                    acc[A_SUMMONING_SICK]++;
                }
                // Exactly the two predicates getCombatPermanentScore uses. canAttack() returns false
                // for a tapped creature before it touches the engine, so tapped creatures cost
                // nothing here; canBlockAny() is the one engine query per creature that this pass
                // adds, and it answers cannot_block and untapped_blockers_diff together.
                if (!p.canAttack(null, game)) {
                    acc[A_CANNOT_ATTACK]++;
                }
                if (!p.canBlockAny(game)) {
                    acc[A_CANNOT_BLOCK]++;
                } else if (!tapped) {
                    acc[A_UNTAPPED_BLOCKERS]++;
                }
            } else if (artifact && p.hasSubtype(SubType.EQUIPMENT, game)) {
                // getFixedPermanentScore's equipment bonus lives in the non-creature branch, which is
                // why this is an else. Gated on artifact because rule 301.5 makes Equipment an
                // artifact subtype, so the gate excludes nothing real while keeping the subtype
                // lookup off every land, creature and enchantment on the board.
                acc[A_EQUIPMENT]++;
            }
            if (land) {
                acc[A_LANDS]++;
                if (!tapped) {
                    acc[A_UNTAPPED_LANDS]++;
                }
            }
            if (artifact) {
                acc[A_ARTIFACTS]++;
            }
            if (types.contains(CardType.ENCHANTMENT)) {
                acc[A_ENCHANTMENTS]++;
            }
            if (planeswalker) {
                acc[A_PLANESWALKERS]++;
            }
            if (tapped && !creature && !land) {
                // getTappedScore's third branch: neither creature nor land. For a non-creature
                // !canTap() is exactly isTapped(), so this is the evaluator's predicate itself.
                acc[A_TAPPED_OTHER]++;
            }
            acc[A_DEPLOYED_MANA_VALUE] += p.getManaValue();

            int damage = p.getDamage();
            if (damage > 0) {
                acc[A_DAMAGE_MARKED] += damage;
            }

            // One read of the counters map serves three features. getCounters(game) hands back the
            // permanent's own field, and the map is empty for the large majority of permanents, so
            // the common case is a single isEmpty() and no hashing at all.
            Counters counters = p.getCounters(game);
            if (!counters.isEmpty()) {
                acc[A_CHARGE_COUNTERS] += counters.getCount(CounterType.CHARGE);
                acc[A_LEVEL_COUNTERS] += counters.getCount(CounterType.LEVEL);
                if (planeswalker) {
                    acc[A_LOYALTY] += counters.getCount(CounterType.LOYALTY);
                }
            }

            // Detrimental auras this seat put on its OWN permanent, counted per detrimental EFFECT
            // exactly as evaluatePermanent counts them. Gated on there being any attachment at all,
            // which there is not for almost every permanent, so nothing below runs in the common
            // case.
            List<UUID> attachments = p.getAttachments();
            if (!attachments.isEmpty()) {
                for (int i = 0, n = attachments.size(); i < n; i++) {
                    Permanent attachment = game.getPermanent(attachments.get(i));
                    // The evaluator dereferences this without a null check and is saved only by a
                    // catch-Throwable several frames up; there is nothing to catch here, so it is
                    // checked. An attachment can be gone from the battlefield in a state the search
                    // is midway through building.
                    if (attachment == null
                            || !attachment.getControllerId().equals(p.getControllerId())) {
                        continue;
                    }
                    for (Ability attached : attachment.getAbilities(game)) {
                        Effects attachedEffects = attached.getEffects();
                        for (int j = 0, m = attachedEffects.size(); j < m; j++) {
                            if (attachedEffects.get(j).getOutcome() == Outcome.Detriment) {
                                acc[A_DETRIMENTAL_AURAS]++;
                            }
                        }
                    }
                }
            }

            // A commander counts for its seat only when that seat also CONTROLS it, which is what
            // the per-player version this replaced did. A stolen commander therefore counts for
            // neither side, deliberately: it is not the thief's commander and it is not on its
            // owner's battlefield.
            //
            // The getMainCard() arm is what makes a DOUBLE-FACED commander count: seatCommanders
            // holds main card ids only (see commanderIdsOf), while a DFC permanent's own id is the
            // id of the face that is up. Both arms are load-bearing -- keep them.
            Set<UUID> ownCommanders = seatCommanders[seat];
            if (!ownCommanders.isEmpty()) {
                Card main = p.getMainCard();
                if (ownCommanders.contains(p.getId())
                        || (main != null && ownCommanders.contains(main.getId()))) {
                    acc[A_COMMANDERS]++;
                }
            }

            Abilities<Ability> abilities = p.getAbilities(game);
            boolean evasive = false;
            boolean manaSource = false;
            // Must-answer signals, per PERMANENT. A_DRAW_ENGINES above counts per ABILITY and keeps
            // that frozen meaning; the evaluator's must-answer signal is "this permanent draws
            // cards", once, however many abilities say so -- which is what these booleans capture.
            boolean drawEngine = false;
            boolean altar = false;
            for (int i = 0, n = abilities.size(); i < n; i++) {
                Ability a = abilities.get(i);
                if (isMine && !manaSource && a instanceof ManaAbility) {
                    manaSource = true;
                }
                if (creature && !evasive && isEvasive(a)) {
                    evasive = true;
                }
                if (hasDrawEffect(a)) {
                    acc[A_DRAW_ENGINES]++;
                    drawEngine = true;
                }
                // Opponents only, matching where the evaluator charges the term. isSacrificeManaEngine
                // is a handful of instanceof tests plus a walk of one small cost list, and it is
                // skipped entirely once an altar ability has been found on this permanent.
                if (!isMine && !altar && ArtificialScoringSystem.isSacrificeManaEngine(a)) {
                    altar = true;
                }
            }
            if (evasive) {
                acc[A_EVASIVE_POWER] += power;
            }
            if (!isMine) {
                // The three context-free signals of ArtificialScoringSystem.getMustAnswerSignals.
                // The fourth (outclasses our board) needs a threshold this pass cannot know yet and
                // comes from oppPowerBuckets; the fifth is board-level and is added after the pass.
                if (drawEngine) {
                    acc[A_MUST_ANSWER]++;
                }
                if (altar) {
                    acc[A_MUST_ANSWER]++;
                }
                if (planeswalker) {
                    acc[A_MUST_ANSWER]++;
                }
            }
            // Counted in every phase now, not only on my own main. unspent_mana_own_turn still
            // reports zero off my main phase -- that is index 14's frozen meaning and it is applied
            // below -- but hand_castable_now needs to know how much mana is open whenever it is
            // asked, and counting the same sources twice for the two uses would be waste.
            if (isMine && manaSource && !tapped) {
                myUntappedSources++;
            }
        }

        // Commander damage: the format's SECOND death clock, and the learner had no feature for it
        // at all while the evaluator was pricing it at commanderDamageWeight=8000.
        //
        // Aggregated as a MAXIMUM, not a sum, for the same reason the evaluator does it that way:
        // the rule is 21 from a single commander, so 15 from each of two commanders is 15 of 21, not
        // 30 of 21. The damage map is usually empty, which the isEmpty() check makes free.
        //
        // seatCommanders holds MAIN card ids only -- see commanderIdsOf, and do not widen it back to
        // all card parts. A half id can never resolve here (the watcher is keyed on the main card
        // id) and adds nothing the main id does not already give; all it buys is a Throwable built
        // inside Watchers.get per doomed lookup, per extraction, per double-faced commander.
        int commanderDamageOut = 0;
        int commanderDamageIn = 0;
        for (int seat = 0; seat < seatCount; seat++) {
            for (UUID commanderId : seatCommanders[seat]) {
                CommanderInfoWatcher watcher = game.getState()
                        .getWatcher(CommanderInfoWatcher.class, commanderId);
                if (watcher == null) {
                    // Not a commander game, or a watcher built without damage tracking.
                    continue;
                }
                Map<UUID, Integer> dealt = watcher.getDamageToPlayer();
                if (dealt.isEmpty()) {
                    continue;
                }
                if (seat == 0) {
                    for (int i = 0; i < oppCount; i++) {
                        commanderDamageOut = Math.max(commanderDamageOut, dealt.getOrDefault(oppIds[i], 0));
                    }
                } else {
                    commanderDamageIn = Math.max(commanderDamageIn, dealt.getOrDefault(playerId, 0));
                }
            }
        }

        // ------------------------------------------------------------------------------------
        // DARRELLBEST-FORK: ONE pass over the stack.
        //
        // Split by kind because the evaluator prices the two kinds differently -- an ability at
        // stackObjectWeight, a spell at its full card-definition score -- so a single count would
        // have to be seeded at one of the two prices and be wrong about the other.
        //
        // Attributed through the same seatOf() the battlefield pass uses, so an object controlled by
        // a player who has already lost counts for nobody, exactly as their permanents do. The stack
        // is empty in the overwhelming majority of evaluated states, and the isEmpty() test means
        // those states do not even allocate an iterator.
        // ------------------------------------------------------------------------------------
        int myStackAbilities = 0;
        int oppStackAbilities = 0;
        int myStackSpells = 0;
        int oppStackSpells = 0;
        if (!game.getStack().isEmpty()) {
            for (StackObject stackObject : game.getStack()) {
                int seat = seatOf(stackObject.getControllerId(), playerId, oppIds, oppCount);
                if (seat < 0) {
                    continue;
                }
                boolean spell = stackObject instanceof Spell;
                if (seat == 0) {
                    if (spell) {
                        myStackSpells++;
                    } else {
                        myStackAbilities++;
                    }
                } else {
                    if (spell) {
                        oppStackSpells++;
                    } else {
                        oppStackAbilities++;
                    }
                }
            }
        }

        // ------------------------------------------------------------------------------------
        // DARRELLBEST-FORK: ONE pass over this player's OWN hand -- see the class note for why that
        // is legitimate where reading an opponent's would not be.
        //
        // Iterates the card ids directly rather than calling getHand().getCards(game), which builds
        // and returns a fresh LinkedHashSet on every call. Same cards, no allocation, and this runs
        // at every leaf.
        // ------------------------------------------------------------------------------------
        int handSize = me.getHand().size();
        int handLands = 0;
        int handCastable = 0;
        int handSpells = 0;
        int handSpellManaValue = 0;
        int interactionHeld = 0;
        if (handSize > 0) {
            for (UUID cardId : me.getHand()) {
                Card card = game.getCard(cardId);
                if (card == null) {
                    continue;
                }
                if (card.isLand(game)) {
                    handLands++;
                    continue;
                }
                handSpells++;
                int manaValue = card.getManaValue();
                handSpellManaValue += manaValue;
                if (manaValue <= myUntappedSources) {
                    handCastable++;
                }
                if (isInteraction(card, game)) {
                    interactionHeld++;
                }
            }
        }

        int myLife = me.getLife();
        double[] f = new double[SIZE];

        f[F_LIFE_DIFF] = myLife - oppLife / div;
        f[F_HAND_SIZE_DIFF] = handSize - oppHand / div;
        f[F_CREATURE_COUNT_DIFF] = diff(mine[A_CREATURES], opps[A_CREATURES], div);
        f[F_CREATURE_POWER_DIFF] = diff(mine[A_POWER], opps[A_POWER], div);
        f[F_CREATURE_TOUGHNESS_DIFF] = diff(mine[A_TOUGHNESS], opps[A_TOUGHNESS], div);
        f[F_LAND_COUNT_DIFF] = diff(mine[A_LANDS], opps[A_LANDS], div);
        f[F_UNTAPPED_LAND_DIFF] = diff(mine[A_UNTAPPED_LANDS], opps[A_UNTAPPED_LANDS], div);
        f[F_ARTIFACT_COUNT_DIFF] = diff(mine[A_ARTIFACTS], opps[A_ARTIFACTS], div);
        f[F_ENCHANTMENT_COUNT_DIFF] = diff(mine[A_ENCHANTMENTS], opps[A_ENCHANTMENTS], div);
        f[F_PLANESWALKER_COUNT_DIFF] = diff(mine[A_PLANESWALKERS], opps[A_PLANESWALKERS], div);
        f[F_COMMANDER_ON_BATTLEFIELD_DIFF] = diff(mine[A_COMMANDERS], opps[A_COMMANDERS], div);
        f[F_LIBRARY_SIZE_DIFF] = me.getLibrary().size() - oppLibrary / div;
        f[F_TURN_NUMBER] = game.getTurnNum();
        f[F_DEPLOYED_MANA_VALUE_DIFF] = diff(mine[A_DEPLOYED_MANA_VALUE], opps[A_DEPLOYED_MANA_VALUE], div);
        // Index 14 keeps its frozen meaning: untapped sources left over on MY OWN main phase, zero
        // everywhere else. The count itself is now taken in every phase because hand_castable_now
        // needs it; the gate moved here rather than being dropped.
        f[F_UNSPENT_MANA_OWN_TURN] = ownMain ? myUntappedSources : 0;
        f[F_DRAW_ENGINE_COUNT_DIFF] = diff(mine[A_DRAW_ENGINES], opps[A_DRAW_ENGINES], div);

        f[F_MY_LIFE] = myLife;
        f[F_MIN_OPP_LIFE] = minOppLife;
        f[F_COMMANDER_DAMAGE_OUT] = Math.min(commanderDamageOut, COMMANDER_DAMAGE_LETHAL);
        f[F_COMMANDER_DAMAGE_IN] = Math.min(commanderDamageIn, COMMANDER_DAMAGE_LETHAL);
        f[F_EVASIVE_POWER_DIFF] = diff(mine[A_EVASIVE_POWER], opps[A_EVASIVE_POWER], div);
        f[F_TAPPED_CREATURE_DIFF] = diff(mine[A_TAPPED_CREATURES], opps[A_TAPPED_CREATURES], div);
        f[F_GRAVEYARD_SIZE_DIFF] = me.getGraveyard().size() - oppGraveyard / div;

        // Lethality: a THRESHOLD, which is the one shape a linear model cannot build out of power
        // and life separately. "20 power against 40 life" and "20 power against 8 life" are the same
        // two numbers to a linear term and completely different board states. Clamped at 2 because
        // everything past "twice lethal" plays identically -- without the clamp a huge board against
        // a player on 1 life produces an enormous input that dominates the gradient.
        if (oppCount == 0) {
            f[F_LETHALITY] = 0.0;
        } else if (minOppLife <= 0) {
            f[F_LETHALITY] = 2.0;
        } else {
            f[F_LETHALITY] = Math.min(2.0, mine[A_POWER] / (double) minOppLife);
        }

        // Life danger: own-life urgency, non-linear on purpose. ArtificialScoringSystem's tabulated
        // curve prices the first point of life at 1000 and every point above 20 at
        // lifeAboveMultiplier=20 -- a 50:1 change in the marginal value of one life point that a
        // single linear coefficient cannot represent at both ends.
        //
        // 1/(1 + life/10) gives a marginal-value ratio of 25:1 between 0 life and 40 life, the same
        // order as the evaluator's 50:1, and stays bounded in [0,1] so it can never blow up the
        // logit the way a reciprocal of life would. A smaller divisor gets closer to 50:1 (1/(1+l/5)
        // is 81:1) but goes nearly flat above 20 life, which is most of a Commander game; /10 stays
        // responsive across the whole 0-40 band the format actually plays in.
        f[F_LIFE_DANGER] = 1.0 / (1.0 + Math.max(myLife, 0) / 10.0);

        // Commander tax proxy. One hash lookup: the watcher already totals casts per player, so
        // nothing has to be counted here.
        CommanderPlaysCountWatcher plays = game.getState().getWatcher(CommanderPlaysCountWatcher.class);
        int commanderCastCount = plays == null ? 0 : plays.getPlayerCount(playerId);
        f[F_COMMANDER_CAST_COUNT] = commanderCastCount;

        // The nine terms the tuned evaluator prices and the learner previously could not see. Each
        // one is the unweighted quantity its parameter multiplies; see the table for the reading of
        // the evaluator that produced each definition.
        f[F_STACK_OBJECTS] = diff(myStackAbilities, oppStackAbilities, div);
        f[F_EQUIPMENT_PERMANENTS] = diff(mine[A_EQUIPMENT], opps[A_EQUIPMENT], div);
        f[F_CHARGE_COUNTERS] = diff(mine[A_CHARGE_COUNTERS], opps[A_CHARGE_COUNTERS], div);
        f[F_LEVEL_COUNTERS] = diff(mine[A_LEVEL_COUNTERS], opps[A_LEVEL_COUNTERS], div);
        f[F_DAMAGE_MARKED] = diff(mine[A_DAMAGE_MARKED], opps[A_DAMAGE_MARKED], div);
        f[F_CANNOT_ATTACK] = diff(mine[A_CANNOT_ATTACK], opps[A_CANNOT_ATTACK], div);
        f[F_CANNOT_BLOCK] = diff(mine[A_CANNOT_BLOCK], opps[A_CANNOT_BLOCK], div);
        f[F_TAPPED_OTHER] = diff(mine[A_TAPPED_OTHER], opps[A_TAPPED_OTHER], div);
        f[F_DETRIMENTAL_OWN_AURAS] = diff(mine[A_DETRIMENTAL_AURAS], opps[A_DETRIMENTAL_AURAS], div);

        // Board texture. Against the opponents' AVERAGE, like every other differential here, so the
        // feature means the same thing in a duel and in a pod.
        f[F_BOARD_TEXTURE] = Math.min(mine[A_CREATURES], opps[A_CREATURES] / div);
        f[F_UNTAPPED_BLOCKERS_DIFF] = diff(mine[A_UNTAPPED_BLOCKERS], opps[A_UNTAPPED_BLOCKERS], div);
        f[F_PLANESWALKER_LOYALTY_DIFF] = diff(mine[A_LOYALTY], opps[A_LOYALTY], div);
        f[F_SUMMONING_SICK_DIFF] = diff(mine[A_SUMMONING_SICK], opps[A_SUMMONING_SICK], div);

        // Evasive clock, on the same shape and for the same reason as lethality above -- a threshold
        // no linear combination of evasive_power_diff and min_opp_life can produce.
        if (oppCount == 0) {
            f[F_EVASIVE_LETHALITY] = 0.0;
        } else if (minOppLife <= 0) {
            f[F_EVASIVE_LETHALITY] = 2.0;
        } else {
            f[F_EVASIVE_LETHALITY] = Math.min(2.0, mine[A_EVASIVE_POWER] / (double) minOppLife);
        }

        f[F_HAND_LAND_COUNT] = handLands;
        f[F_HAND_CASTABLE_NOW] = handCastable;
        // Guarded division: a hand of nothing but lands has no spells to average, and 0/0 is NaN,
        // which the sigmoid turns into a NaN logit and from there into weights that are permanently
        // NaN -- silent, unrecoverable, and indistinguishable from the bot simply playing badly.
        f[F_HAND_AVG_MANA_VALUE] = handSpells == 0 ? 0.0 : handSpellManaValue / (double) handSpells;
        f[F_INTERACTION_HELD] = interactionHeld;
        f[F_STACK_SPELL_DIFF] = diff(myStackSpells, oppStackSpells, div);

        // ------------------------------------------------------------------------------------
        // DARRELLBEST-FORK: the three terms appended to the evaluator with these features. Each is
        // the same quantity GameStateEvaluator2 multiplies its weight by, so that feature * weight
        // reproduces the evaluator's own contribution and the seed stays a copy of the tuned bot
        // rather than an approximation of it.
        // ------------------------------------------------------------------------------------

        // Must-answer signals on the opponents' boards. Three of the five were counted during the
        // pass; the fourth is the outclass test, whose threshold could only be computed once our own
        // creatures had all been seen.
        int outclassThreshold = ArtificialScoringSystem.getOutclassThreshold(
                mine[A_CREATURES], mine[A_POWER]);
        int mustAnswer = opps[A_MUST_ANSWER];
        for (int bucket = outclassThreshold; bucket < oppPowerBuckets.length; bucket++) {
            mustAnswer += oppPowerBuckets[bucket];
        }
        // The board-level signal: a board whose total power already matches our life is a board in
        // which every creature is part of a lethal attack. The evaluator asks this of the ONE
        // opponent it scores against; here it is the whole table's power against our life, which is
        // the same question in a duel and the more accurate one in a pod.
        if (opps[A_POWER] > 0 && opps[A_POWER] >= myLife) {
            mustAnswer += opps[A_CREATURES];
        }
        f[F_OPP_MUST_ANSWER] = mustAnswer / div;

        // Creatures deployed past the point where more board buys nothing. Floored at 0 because the
        // evaluator charges nothing below the threshold -- an un-floored value would make the model
        // read "behind on board" as the negative of "overextended", which is not what the term says
        // and is already carried by creature_count_diff.
        double surplus = mine[A_CREATURES] - opps[A_CREATURES] / div
                - ArtificialScoringSystem.OVEREXTENSION_MARGIN;
        f[F_OVEREXTENSION] = Math.max(0.0, surplus);

        // Commander tax we could not pay right now, and zero while the commander is out.
        //
        // The battlefield test is A_COMMANDERS (our commanders we control), where the evaluator uses
        // game.isCommanderObject on each of our permanents. Same question, different route: both
        // count only a commander that is ours AND on our battlefield, so a stolen one does not make
        // either side think the commander is safe.
        double recastTax = 0.0;
        if (mine[A_COMMANDERS] == 0) {
            int unpayable = ArtificialScoringSystem.COMMANDER_TAX_PER_CAST * commanderCastCount
                    - myUntappedSources;
            if (unpayable > 0) {
                recastTax = unpayable;
            }
        }
        f[F_COMMANDER_RECAST_TAX] = recastTax;

        return f;
    }

    private static double diff(int mine, int theirs, double div) {
        return mine - theirs / div;
    }

    private static int seatOf(UUID controllerId, UUID playerId, UUID[] oppIds, int oppCount) {
        if (playerId.equals(controllerId)) {
            return 0;
        }
        // Linear scan over at most three UUIDs beats hashing one, and this runs per permanent.
        for (int i = 0; i < oppCount; i++) {
            if (oppIds[i].equals(controllerId)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * DARRELLBEST-FORK: this player's commander <b>main card</b> ids.
     * <p>
     * The version this replaced threw a NullPointerException on every call that reached it, which is
     * what "the learner AI is broken" turned out to be. It called
     * {@code getCommanderCardsFromAnyZones(player, null, null)}; the third parameter is a
     * {@code Zone...} varargs, and an explicit null becomes a null array, so the method's own
     * {@code Arrays.stream(searchZones)} threw immediately. It only reached that call once the
     * player controlled a permanent, so opening turns looked fine and the failure appeared later.
     * <p>
     * The exception was swallowed upstream: games continued, a weights file was still written, and
     * nothing reported an error -- so the learner appeared to run while its feature vector was never
     * actually produced. Any weights trained before that fix are meaningless.
     * <p>
     * getCommandersIds is also much cheaper than the zone search.
     * <p>
     * DARRELLBEST-FORK: {@code returnAllCardParts} is now <b>false</b>. This is a WASTE fix, not a
     * correctness one -- be precise about which, because the neighbouring bug in
     * {@code GameStateEvaluator2.commanderDamagePenalty} looked identical and was not.
     * <p>
     * {@code CommanderInfoWatcher} is registered once per commander under the MAIN card id
     * ({@code GameCommanderImpl.initCommander} iterates {@code getCommandersIds(..., false)} and
     * passes {@code commander.getId()}, GameCommanderImpl.java:125-154; key format is
     * {@code sourceId.toString() + "CommanderInfoWatcher"}, Watcher.getKey). With {@code true} this
     * returned the main card id PLUS both half ids (CardUtil.getObjectParts). The main id is in that
     * set, so the commander-damage loop in {@link #extract} DID resolve the watcher and
     * commander_damage_out/in were correct all along -- verified on the bench, which reported
     * nonzero values for a double-faced commander even before this change.
     * <p>
     * What the two extra half ids bought was two guaranteed-doomed lookups per double-faced
     * commander per extraction, and {@code Watchers.get} does not fail cheaply: it builds a
     * {@code new Throwable()} and concatenates the key into a message BEFORE the logger decides
     * whether to print (Watchers.java:51), so suppressing the category costs the same as printing
     * it. On the reproducing matchup (Saryth vs PeterParker, seed 606) that was ~3,000 of the 6,054
     * misses in a single game, from this method alone.
     * <p>
     * Passing {@code false} asks for exactly the id set {@code initCommander} registered under, so
     * every lookup resolves by construction rather than by luck of the card's layout.
     * <p>
     * Safe for the battlefield commander match in {@link #extract}, the other reader of this set: it
     * tests {@code p.getId()} OR {@code p.getMainCard().getId()}, and a double-faced commander
     * permanent carries a half id whose main card is exactly what this now returns. That result is
     * therefore unchanged -- commander_on_battlefield_diff does not move, so no feature drifts and
     * no trained model is invalidated by this edit.
     */
    private static Set<UUID> commanderIdsOf(Player player, Game game) {
        if (player == null) {
            return Collections.emptySet();
        }
        return game.getCommandersIds(player, CommanderCardType.ANY, false);
    }

    /**
     * DARRELLBEST-FORK: evasion by {@code instanceof}, NOT by rule text.
     * <p>
     * The obvious alternative is the scoring path's {@code MagicAbility}, which is a
     * {@code HashMap<String,Integer>} keyed on {@code ability.getRule()} -- so asking it costs a
     * rule-text render (StringBuilder, string concatenation) plus a String hash, per ability. That
     * is roughly 460ns against roughly 57ns for these checks, and this runs for every ability of
     * every creature at every search leaf.
     * <p>
     * {@link EvasionAbility} is a real superclass and covers flying, shadow, fear, intimidate,
     * horsemanship, landwalk and {@code CantBeBlockedSourceAbility} in one test. Menace, trample and
     * skulk extend {@code StaticAbility} directly and have to be named individually -- checked, not
     * assumed; a single {@code instanceof EvasionAbility} silently misses all three.
     */
    private static boolean isEvasive(Ability a) {
        return a instanceof EvasionAbility
                || a instanceof MenaceAbility
                || a instanceof TrampleAbility
                || a instanceof SkulkAbility;
    }

    /**
     * Counts an ability at most once however many draw effects it has, which is what the loop this
     * replaced did (it broke out of the effect loop on the first hit).
     * <p>
     * Iterates the modes directly instead of calling {@code getAllEffects()}, which builds and
     * returns a fresh {@code Effects} list on every call. Same effects, same order, no allocation --
     * and allocation is what matters here, because this ran for every ability of every permanent at
     * every leaf and the garbage it produced was pure overhead.
     */
    private static boolean hasDrawEffect(Ability ability) {
        for (Mode mode : ability.getModes().values()) {
            Effects effects = mode.getEffects();
            for (int i = 0, n = effects.size(); i < n; i++) {
                if (isDrawEffectClass(effects.get(i).getClass())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDrawEffectClass(Class<?> effectClass) {
        Boolean known = DRAW_EFFECT_BY_CLASS.get(effectClass);
        if (known == null) {
            known = effectClass.getSimpleName().contains("DrawCard");
            DRAW_EFFECT_BY_CLASS.put(effectClass, known);
        }
        return known;
    }

    /**
     * DARRELLBEST-FORK: is this card in hand a piece of interaction -- removal, burn, or a counter?
     * <p>
     * Answered from {@link Outcome}, which every effect already declares, rather than from rule text.
     * Rule text would mean {@code getRule()}, a StringBuilder render per effect, which is exactly the
     * cost {@link #isEvasive} was written to avoid; and a name list would need maintaining forever
     * and would still miss anything printed after it was written.
     * <p>
     * Restricted to instants and sorceries. That deliberately misses creature-based interaction (an
     * ETB that kills something, a Swords in the form of a 2/2), but it removes the false positives
     * that matter -- almost every creature has SOME effect the engine marks Detriment, and a feature
     * that counts most of the hand is a feature that says nothing.
     * <p>
     * Memoised per CLASS: every printed card in XMage is its own class, so the class is the printed
     * card, and the answer is a property of the printed card alone. Computed once per distinct card
     * the JVM ever sees; every later leaf pays one hash lookup.
     */
    private static boolean isInteraction(Card card, Game game) {
        Class<?> cardClass = card.getClass();
        Boolean known = INTERACTION_BY_CLASS.get(cardClass);
        if (known == null) {
            known = computeInteraction(card, game);
            INTERACTION_BY_CLASS.put(cardClass, known);
        }
        return known;
    }

    private static boolean computeInteraction(Card card, Game game) {
        if (!card.isInstantOrSorcery(game)) {
            return false;
        }
        for (Ability ability : card.getAbilities(game)) {
            // getAllEffects() rather than getEffects(): a modal removal spell keeps its removal in a
            // non-default mode, and this runs once per card class ever, so the allocation
            // getAllEffects() makes is paid once rather than per leaf.
            Effects effects = ability.getAllEffects();
            for (int i = 0, n = effects.size(); i < n; i++) {
                if (isHostileOutcome(effects.get(i).getOutcome())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The outcomes that mean "this is pointed at something of theirs". {@code Detriment} is in the
     * list because that is what {@code CounterTargetEffect} declares, so leaving it out would drop
     * every counterspell -- checked in the source, not assumed from the name.
     */
    private static boolean isHostileOutcome(Outcome outcome) {
        return outcome == Outcome.DestroyPermanent
                || outcome == Outcome.Exile
                || outcome == Outcome.Damage
                || outcome == Outcome.Removal
                || outcome == Outcome.Detriment;
    }

    /**
     * DARRELLBEST-FORK: the hand-tuned evaluator expressed as a weight vector over these features,
     * so the learned model STARTS as the tuned bot instead of starting from nothing.
     * <p>
     * This is the point of the whole design. A zero vector predicts 0.5 everywhere and knows none of
     * what the hand tuning established -- commander damage, the life curve, stack scoring, draw
     * engines, board development. Starting there means the model has to rediscover all of it before
     * it is even break-even, which is why trust had to ramp over 500 games and why a 16-feature
     * linear model looked likely to LOSE to the evaluator it was blending against.
     * <p>
     * Seeded instead, the model begins equivalent to the tuned evaluator and every update is a
     * departure from a good position rather than a crawl toward one. Learning can then only be
     * measured as "better or worse than hand-tuned", which is the question that actually matters.
     * <p>
     * <b>The {@code * scale} is not cosmetic; leaving it out silently broke the seed.</b> This wrote
     * {@code w[i] = param * LOGIT_SCALE}, which is correct only if the model computes
     * {@code logit = sum(w[i] * f[i])}. {@link OnlineTDLearner#predict} actually computes
     * {@code sum(w[i] * f[i] / scale[i])}, and the scales run from 1 to 60. So every seeded weight
     * was quietly divided by a different constant on its way into the model: life_diff by 40,
     * library_size_diff by 60, commander_on_battlefield_diff by 1. The seeded model was therefore
     * not the tuned evaluator, was not any consistent rescaling of it, and had the RELATIVE
     * importance of its terms scrambled by up to 60x. Multiplying the scale back in here makes the
     * effective coefficient on the raw feature value exactly {@code param * LOGIT_SCALE}, whatever
     * the normaliser is -- which is the invariant {@code StateFeatureSeedTest} now pins down.
     * <p>
     * Built by iterating the feature table rather than by hardcoded indices, so a feature appended
     * to the table can never again be left out of the seed.
     * <p>
     * Features with no honest tuned counterpart seed to 0: the tuned evaluator does not use them, so
     * the model starts agnostic and may learn them. Inventing a correspondence would be worse than
     * zero, because the seed's whole claim is that it IS the tuned bot.
     */
    public static double[] seedFromParams(CommanderEvalParams p) {
        double[] w = new double[SIZE];
        for (int i = 0; i < FEATURES.length; i++) {
            Feature feature = FEATURES[i];
            if (feature.seed != null) {
                w[i] = feature.seed.applyAsInt(p) * LOGIT_SCALE * feature.scale;
            }
        }
        return w;
    }

    /**
     * Maps evaluator score units onto logit units. A commanding position in this evaluator is worth
     * a few thousand points and a logit of about 3 is a 95% win probability, so this is calibration,
     * not tuning -- getting it wrong makes the model over- or under-confident, not wrong about which
     * side is ahead.
     * <p>
     * DARRELLBEST-FORK: lowered from 1e-3 to 2e-4 when the seed was fixed to land in logit space.
     * 1e-3 was chosen while the seed was being silently divided by the per-feature scales, so it was
     * calibrated against weights up to 60x smaller than intended. Applied to a correctly seeded
     * model it is badly over-confident: on a 33k-position offline corpus it gives a log-loss of 0.83
     * with about 15% of positions past |z| > 6, i.e. the sigmoid saturated and the gradient dead.
     * 2e-4 on the same corpus reaches log-loss 0.603 at AUC 0.729.
     * <p>
     * Public because it is half of the seed invariant, and a test that had to re-derive it could
     * agree with a wrong value.
     */
    public static final double LOGIT_SCALE = 2.0e-4;

    private static String[] namesOf(Feature[] features) {
        String[] names = new String[features.length];
        for (int i = 0; i < features.length; i++) {
            names[i] = features[i].name;
        }
        return names;
    }

    private static double[] scalesOf(Feature[] features) {
        double[] scales = new double[features.length];
        for (int i = 0; i < features.length; i++) {
            if (features[i].scale == 0.0) {
                throw new ExceptionInInitializerError(
                        "feature " + features[i].name + " has scale 0 -- OnlineTDLearner divides by it");
            }
            scales[i] = features[i].scale;
        }
        return scales;
    }

    private static int indexOf(String name) {
        for (int i = 0; i < FEATURES.length; i++) {
            if (FEATURES[i].name.equals(name)) {
                return i;
            }
        }
        throw new ExceptionInInitializerError("no feature named " + name + " in StateFeatures.FEATURES");
    }
}
