package mage.player.ai.commander.score;

import java.util.Arrays;

/**
 * DARRELLBEST-FORK: every magic number the heuristic evaluator uses, in one immutable object.
 * <p>
 * Before this, the weights were {@code private static final} constants inside
 * {@link ArtificialScoringSystem} and {@link GameStateEvaluator2}. That made them unreachable: a
 * tuner cannot edit a constant at runtime, two bots cannot disagree about what a point of life is
 * worth, and an A/B of two weight vectors needs two builds. Hoisting them here makes the evaluation
 * function a value that a player HOLDS rather than a property of the classpath.
 * <p>
 * <b>{@link #DEFAULT} reproduces the previous hard-coded behaviour exactly.</b> Every field's
 * default is the literal that used to sit at the call site, and the arithmetic at each call site was
 * kept in the same shape (same float/int mix, same operator order) so that substituting the defaults
 * is a compile-time-only change. A bot constructed without params plays bit-for-bit as it did.
 * <p>
 * <b>Immutable, and shared by reference.</b> Copies of a player (the search makes thousands) share
 * one instance rather than each holding a copy, the same way {@code ComputerPlayerLearner} shares its
 * {@code federation} and {@code session}. That is safe only because nothing here can be mutated after
 * construction -- {@link #lifeScores} is defensively copied in and is never handed back as an array,
 * only read through {@link #getLifeScoreAt(int)}. Sharing also means the search's thousands of player
 * copies cost nothing per copy, which matters on a hot path.
 * <p>
 * <b>What is deliberately NOT here.</b>
 * <ul>
 *   <li>{@code WIN_GAME_SCORE}/{@code LOSE_GAME_SCORE} (+/-100000000) are structural sentinels, not
 *       weights. {@code ComputerPlayer6} tests search results against them for EXACT equality to
 *       detect a forced win/loss, and {@code ComputerPlayerLearner} maps a win probability onto a
 *       range chosen to sit well inside their magnitude. Making them tunable would let a tuner
 *       silently break "a win outscores every non-win", so they stay {@code static final}.</li>
 *   <li>{@code ArtificialScoringSystem}'s {@code UNKNOWN_CARD_SCORE}, {@code getManaScore} and
 *       {@code getAttackerScore} are dead -- no caller anywhere in the repo. Parameterising them
 *       would advertise knobs that do nothing.</li>
 *   <li>{@code passivityPenalty} lives in the shared upstream {@code ComputerPlayer} and is not this
 *       fork's to change.</li>
 * </ul>
 *
 * @author Darrell Best
 */
public final class CommanderEvalParams {

    /**
     * The historical hand-tuned weights. Behaviour with this instance is identical to the code
     * before the weights were extracted.
     */
    public static final CommanderEvalParams DEFAULT = builder().build();

    /**
     * DARRELLBEST-FORK: the weights the live Commander bot actually plays with.
     * <p>
     * Kept SEPARATE from {@link #DEFAULT} on purpose. DEFAULT must keep meaning "upstream's
     * historical behaviour" — the equivalence tests pin exact evaluator output against values
     * hand-derived from the pre-refactor literals, and the G0 instrument control rests on a
     * default-configured bot being identical to the old one. Folding a tuned value into DEFAULT
     * would quietly destroy both, and there would be nothing left to measure future changes against.
     * <p>
     * <b>handCardScore 5 → 150.</b> At 5, a card in hand was worth 1/60th of a permanent
     * ({@code permanentOnBattlefieldBonus} alone is 300) — measured live at 20 points of hand
     * against 12,926 of board, i.e. 0.15% of the position. The bot was effectively unable to value
     * holding a counterspell.
     * <p>
     * Measured over 161 decisive games in three independent runs across two very different decks:
     * <pre>
     *   Krenko mirror (aggro)     34-25 of 59   57.6%
     *   Kairi v6 mirror (control) 24-17 of 41   58.5%
     *   Krenko mirror (2nd run)   35-26 of 61   57.4%
     *   POOLED                    93-68 of 161  57.8%  95% CI [50.04%, 65.13%]
     * </pre>
     * The interval's lower bound clears 50% by 0.04 points and the exact binomial p is 0.058, so
     * this is on the threshold rather than comfortably past it. What carries it is replication:
     * three independent samples across an aggro deck and a control deck landing within 1.1 points
     * of each other. Roughly 20-40 more decisive games would settle it properly.
     * <p>
     * <b>commanderDamageWeight 0 → 8000: included on domain grounds, NOT on measurement.</b> Be
     * clear about the difference. The 1v1 Krenko screen returned 57.1%, 95% CI [42.2%, 70.9%] —
     * inconclusive. But that fixture is mono-red aggro in a duel, where 21 commander damage from a
     * single source almost never decides anything, so the experiment could barely observe the
     * mechanic it was testing. Inconclusive there is not evidence of no value in multiplayer
     * Commander, which is what the live server actually runs and where a commander connecting three
     * times is a normal way to die.
     * <p>
     * Without this term a player on 35 life who has taken 18 commander damage scores as healthy
     * while being one hit from losing — a second lethal axis the evaluator simply could not see.
     * <p>
     * On the value: 8000 is the level that was actually screened, so it is preferred over an
     * untested number. A case exists for <b>12000</b> instead — that would make 21 commander damage
     * cost exactly what draining a full 40 life costs ({@code getLifeScore(40) == 12000}), putting
     * the two death clocks on one scale. Worth a run before changing it; do not swap it in blind.
     * <p>
     * <b>Neither value is verifiable with today's harness.</b> Mage.Bench runs only two-player and
     * Commander DUEL, so the format this term exists for cannot be benchmarked at all until the
     * harness supports Free For All.
     */
    public static final CommanderEvalParams TUNED = DEFAULT.toBuilder()
            .handCardScore(60)
            .commanderDamageWeight(8000)
            .modeSelectionMode(1)
            .attackAggression(2)
            .multiplayerAttackSplit(1)
            .declineLosingManaPayments(1)
            .smartMulligan(1)
            .stackObjectWeight(150)
            .drawEngineBonus(400)
            .commanderPermanentBonus(900)
            .blockTradeMode(1)
            .commanderBlockPenalty(1200)
            .build();

    // --- life ---
    private final int[] lifeScores;
    private final int lifeAboveMultiplier;

    // --- hand ---
    private final int handCardScore;
    private final int commanderDamageWeight;
    private final int opponentSelectionMode;
    private final int modeSelectionMode;
    private final int attackAggression;
    private final int multiplayerAttackSplit;
    private final int declineLosingManaPayments;
    private final int smartMulligan;
    private final int stackObjectWeight;
    private final int commanderPermanentBonus;
    private final int blockTradeMode;
    private final int commanderBlockPenalty;
    private final int unspentManaPenalty;
    private final int deployedManaValueWeight;
    private final int drawEngineBonus;

    // --- card definition ---
    private final int baseCardValue;
    private final int landBaseMultiplier;
    private final int landPerManaSymbol;
    private final int nonLandBaseMultiplier;
    private final int manaValuePenaltyPerPip;
    private final int cardPowerToughnessMultiplier;
    private final int rarityMultiplier;

    // --- permanents ---
    private final int permanentOnBattlefieldBonus;
    private final int equipmentPermanentBonus;
    private final int chargeCounterScore;
    private final int levelCounterScore;
    private final int damageMarkedPenalty;
    private final int creaturePowerMultiplier;
    private final int creatureToughnessMultiplier;
    private final int abilityScorePowerOffset;
    private final int abilityScoreDivisor;
    private final int attachedEnchantmentOutcomeMultiplier;
    private final int attachedEquipmentOutcomeMultiplier;

    // --- combat / tapped ---
    private final int cannotAttackPenalty;
    private final int cannotBlockPenalty;
    private final int tappedCreaturePenalty;
    private final int tappedLandPenalty;
    private final int tappedOtherPenalty;

    // --- misc penalties ---
    private final int detrimentalOwnAuraPenalty;

    private CommanderEvalParams(Builder b) {
        this.lifeScores = Arrays.copyOf(b.lifeScores, b.lifeScores.length);
        this.lifeAboveMultiplier = b.lifeAboveMultiplier;
        this.handCardScore = b.handCardScore;
        this.commanderDamageWeight = b.commanderDamageWeight;
        this.opponentSelectionMode = b.opponentSelectionMode;
        this.modeSelectionMode = b.modeSelectionMode;
        this.attackAggression = b.attackAggression;
        this.multiplayerAttackSplit = b.multiplayerAttackSplit;
        this.declineLosingManaPayments = b.declineLosingManaPayments;
        this.smartMulligan = b.smartMulligan;
        this.stackObjectWeight = b.stackObjectWeight;
        this.commanderPermanentBonus = b.commanderPermanentBonus;
        this.blockTradeMode = b.blockTradeMode;
        this.commanderBlockPenalty = b.commanderBlockPenalty;
        this.unspentManaPenalty = b.unspentManaPenalty;
        this.deployedManaValueWeight = b.deployedManaValueWeight;
        this.drawEngineBonus = b.drawEngineBonus;
        this.baseCardValue = b.baseCardValue;
        this.landBaseMultiplier = b.landBaseMultiplier;
        this.landPerManaSymbol = b.landPerManaSymbol;
        this.nonLandBaseMultiplier = b.nonLandBaseMultiplier;
        this.manaValuePenaltyPerPip = b.manaValuePenaltyPerPip;
        this.cardPowerToughnessMultiplier = b.cardPowerToughnessMultiplier;
        this.rarityMultiplier = b.rarityMultiplier;
        this.permanentOnBattlefieldBonus = b.permanentOnBattlefieldBonus;
        this.equipmentPermanentBonus = b.equipmentPermanentBonus;
        this.chargeCounterScore = b.chargeCounterScore;
        this.levelCounterScore = b.levelCounterScore;
        this.damageMarkedPenalty = b.damageMarkedPenalty;
        this.creaturePowerMultiplier = b.creaturePowerMultiplier;
        this.creatureToughnessMultiplier = b.creatureToughnessMultiplier;
        this.abilityScorePowerOffset = b.abilityScorePowerOffset;
        this.abilityScoreDivisor = b.abilityScoreDivisor;
        this.attachedEnchantmentOutcomeMultiplier = b.attachedEnchantmentOutcomeMultiplier;
        this.attachedEquipmentOutcomeMultiplier = b.attachedEquipmentOutcomeMultiplier;
        this.cannotAttackPenalty = b.cannotAttackPenalty;
        this.cannotBlockPenalty = b.cannotBlockPenalty;
        this.tappedCreaturePenalty = b.tappedCreaturePenalty;
        this.tappedLandPenalty = b.tappedLandPenalty;
        this.tappedOtherPenalty = b.tappedOtherPenalty;
        this.detrimentalOwnAuraPenalty = b.detrimentalOwnAuraPenalty;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return a builder pre-loaded with this instance's values, for deriving a variant that differs
     *         in one or two weights without restating the other twenty-six
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.lifeScores = Arrays.copyOf(this.lifeScores, this.lifeScores.length);
        b.lifeAboveMultiplier = this.lifeAboveMultiplier;
        b.handCardScore = this.handCardScore;
        b.commanderDamageWeight = this.commanderDamageWeight;
        b.opponentSelectionMode = this.opponentSelectionMode;
        b.modeSelectionMode = this.modeSelectionMode;
        b.attackAggression = this.attackAggression;
        b.multiplayerAttackSplit = this.multiplayerAttackSplit;
        b.declineLosingManaPayments = this.declineLosingManaPayments;
        b.smartMulligan = this.smartMulligan;
        b.stackObjectWeight = this.stackObjectWeight;
        b.commanderPermanentBonus = this.commanderPermanentBonus;
        b.blockTradeMode = this.blockTradeMode;
        b.commanderBlockPenalty = this.commanderBlockPenalty;
        b.unspentManaPenalty = this.unspentManaPenalty;
        b.deployedManaValueWeight = this.deployedManaValueWeight;
        b.drawEngineBonus = this.drawEngineBonus;
        b.baseCardValue = this.baseCardValue;
        b.landBaseMultiplier = this.landBaseMultiplier;
        b.landPerManaSymbol = this.landPerManaSymbol;
        b.nonLandBaseMultiplier = this.nonLandBaseMultiplier;
        b.manaValuePenaltyPerPip = this.manaValuePenaltyPerPip;
        b.cardPowerToughnessMultiplier = this.cardPowerToughnessMultiplier;
        b.rarityMultiplier = this.rarityMultiplier;
        b.permanentOnBattlefieldBonus = this.permanentOnBattlefieldBonus;
        b.equipmentPermanentBonus = this.equipmentPermanentBonus;
        b.chargeCounterScore = this.chargeCounterScore;
        b.levelCounterScore = this.levelCounterScore;
        b.damageMarkedPenalty = this.damageMarkedPenalty;
        b.creaturePowerMultiplier = this.creaturePowerMultiplier;
        b.creatureToughnessMultiplier = this.creatureToughnessMultiplier;
        b.abilityScorePowerOffset = this.abilityScorePowerOffset;
        b.abilityScoreDivisor = this.abilityScoreDivisor;
        b.attachedEnchantmentOutcomeMultiplier = this.attachedEnchantmentOutcomeMultiplier;
        b.attachedEquipmentOutcomeMultiplier = this.attachedEquipmentOutcomeMultiplier;
        b.cannotAttackPenalty = this.cannotAttackPenalty;
        b.cannotBlockPenalty = this.cannotBlockPenalty;
        b.tappedCreaturePenalty = this.tappedCreaturePenalty;
        b.tappedLandPenalty = this.tappedLandPenalty;
        b.tappedOtherPenalty = this.tappedOtherPenalty;
        b.detrimentalOwnAuraPenalty = this.detrimentalOwnAuraPenalty;
        return b;
    }

    // --- life ---

    /**
     * The highest life total the score table covers. DERIVED from the table's length rather than
     * stored: a separately stored maximum can disagree with the table it indexes, and the failure
     * mode of that disagreement is either an out-of-bounds read or a silently truncated curve.
     */
    public int getMaxTabulatedLife() {
        return lifeScores.length - 1;
    }

    /**
     * @param life a life total in {@code [0, getMaxTabulatedLife()]}
     * @return the tabulated score for that life total
     */
    public int getLifeScoreAt(int life) {
        return lifeScores[life];
    }

    /** Score per point of life ABOVE {@link #getMaxTabulatedLife()}, where the curve goes flat. */
    public int getLifeAboveMultiplier() {
        return lifeAboveMultiplier;
    }

    // --- hand ---

    public int getHandCardScore() {
        return handCardScore;
    }

    /**
     * DARRELLBEST-FORK: how much being dead to commander damage is worth, on the same scale as the
     * life score. 0 (the default) disables the term entirely, keeping DEFAULT bit-identical to the
     * historical evaluator, which had no concept of commander damage at all.
     */
    public int getCommanderDamageWeight() {
        return commanderDamageWeight;
    }

    /**
     * DARRELLBEST-FORK: which opponent the evaluator scores against in a multiplayer game.
     * <p>
     * {@code 0} (default) keeps upstream behaviour exactly: the FIRST opponent returned by
     * {@code game.getOpponents(...)}, an arbitrary one. In a Free For All that means the bot scores
     * the board as though two of its three opponents do not exist -- it cannot see a lethal board
     * across the table.
     * <p>
     * {@code 1} scores against the MOST THREATENING opponent (highest life + permanents + hand).
     * Deliberately still one opponent rather than a sum: it leaves every downstream term unchanged,
     * and in a free-for-all you lose to whoever is strongest, not to the average. The cost is that
     * every evaluation now scores all opponents' boards to find the maximum, which is roughly Nx the
     * permanent scoring in an N-opponent game -- real, on a bot that already logs
     * "AI player thinks too long" on large boards.
     */
    public int getOpponentSelectionMode() {
        return opponentSelectionMode;
    }

    /**
     * DARRELLBEST-FORK: how a modal ability picks its mode.
     * <p>
     * {@code 0} (default) is upstream: {@code .findFirst()} over the legal modes — the mode declared
     * first in the card's source, every time, regardless of value. That is not a choice, it is
     * declaration order.
     * <p>
     * {@code 1} scores the modes and takes the best. The case that motivated it: Kairi, the Swirling
     * Sky's dies-trigger is "choose one — return any number of target nonland permanents with total
     * mana value 6 or less to their OWNERS' hands; or mill six, then return up to two instants
     * and/or sorceries from your graveyard to your hand". Bounce is declared first, and upstream's
     * only filter is {@code canChoose} — legality, not value. So when the sole legal bounce targets
     * are the bot's OWN permanents, it bounces its own board rather than taking the mill mode and
     * drawing two cards. Actively harmful, not merely suboptimal.
     */
    public int getModeSelectionMode() {
        return modeSelectionMode;
    }

    /**
     * DARRELLBEST-FORK: how willing the bot is to attack into possible blockers.
     * <p>
     * {@code 0} (default) is upstream, and it is extremely passive. {@code declareAttackers} attacks
     * only when it can kill an opponent OUTRIGHT, or with creatures that are "safe" — meaning no
     * possible blocker could kill them. One large untapped blocker therefore shuts down the entire
     * attack step. Caught in a live audit log as
     * {@code NO ATTACKS | T6 | 3 untapped creature(s) available}.
     * <p>
     * {@code 1} additionally attacks when the board is WIDER than the defence: if there are more
     * available attackers than possible blockers, the surplus connects no matter how blocks are
     * assigned, so the attack is profitable even though individual attackers are not "safe". This is
     * the mode that suits token and go-wide decks, where the whole plan is to out-number.
     * <p>
     * {@code 2} also accepts a FAVOURABLE TRADE: attack even into a lethal blocker when the attacker
     * is worth materially less than the blocker that would have to eat it. Trading a 1/1 token for a
     * 5/5 is good play that mode 0 refuses on principle.
     * <p>
     * Never bypasses the 0-power check — attacking with a 0-power creature is pointless at any
     * aggression level.
     */
    public int getAttackAggression() {
        return attackAggression;
    }

    /**
     * DARRELLBEST-FORK: whether attacks may be SPLIT across several opponents.
     * <p>
     * {@code 0} (default) is upstream, and in a multiplayer game it is badly wrong. The
     * "any remaining attackers go for the player" loop in {@code declareAttackers} sits INSIDE the
     * per-defender loop, so on the FIRST opponent it declares every remaining attacker against them;
     * every later opponent then finds {@code isAttacking()} already true and is skipped. The bot
     * therefore dumps its whole team on whoever happens to come first in the opponent list and can
     * never divide its attack — in Free For All, which is what the live server runs.
     * <p>
     * {@code 1} distributes instead: send exactly enough power to kill an opponent when that is
     * available, otherwise an even share, leaving the remainder for the opponents still to come.
     * <p>
     * The assignment is deliberately GREEDY, one pass over attackers per defender. Searching
     * assignments of N attackers across M defenders is M^N, which is precisely the kind of explosion
     * that already makes this bot time out on large boards; a good split found cheaply beats an
     * optimal one that never finishes.
     */
    public int getMultiplayerAttackSplit() {
        return multiplayerAttackSplit;
    }

    /**
     * DARRELLBEST-FORK: refuse an optional mana payment that costs at least what its source makes.
     * <p>
     * {@code 0} (default) keeps upstream's unconditional yes to every optional cost. {@code 1}
     * declines mana-for-mana losses on our own mana rocks — the Mana Vault case, where the bot pays
     * {4} every upkeep to untap something that taps for {C}{C}{C}.
     */
    public int getDeclineLosingManaPayments() {
        return declineLosingManaPayments;
    }

    /**
     * DARRELLBEST-FORK: protect lands when putting cards on the bottom after a London mulligan.
     * <p>
     * {@code 0} is upstream, which answers the bottoming prompt with generic target logic that knows
     * nothing about mulligans and will bottom the lands. Observed live: six consecutive turns with
     * no land drop, first land on turn 7. {@code 1} bottoms the most expensive spells first and
     * keeps a workable land count.
     */
    public int getSmartMulligan() {
        return smartMulligan;
    }

    /**
     * DARRELLBEST-FORK: value of a triggered/activated ability of OURS waiting on the stack.
     * <p>
     * The evaluator did not look at the stack at all, which made casting a spell look like a
     * disaster: the card leaves hand (-handCardScore, now 150), the lands become tapped, and the
     * spell itself is worth NOTHING until it resolves. ComputerPlayer6.addActions then abandons any
     * branch whose score drops ("if (testScore &lt; currentScore)"), so the search would refuse to
     * explore casting at all -- the bot sat on a full hand and untapped mana, then spent the
     * opponent's turn cracking Clues and tapping lands, exactly as reported.
     * <p>
     * Spells on the stack are scored at their card value, which makes casting roughly
     * score-neutral instead of a cliff. Abilities on the stack get this flat weight, signed by
     * controller: our own triggers are pending value, an opponent's are pending problems. A wide
     * board that triggers many times is a good position, and the evaluator should say so.
     * <p>
     * 0 (default) keeps the stack invisible, as upstream.
     */
    public int getStackObjectWeight() {
        return stackObjectWeight;
    }

    /**
     * DARRELLBEST-FORK: extra value for a permanent that GENERATES CARDS.
     * <p>
     * The evaluator scored Rhystic Study, Mystic Remora and Guardian Project exactly like any other
     * 300-point enchantment: it has no concept that a permanent produces cards over time. So the bot
     * had no reason to prioritise, protect, or build around a draw engine, and no reason to remove
     * an opponent's -- it saw a card-advantage machine and a vanilla trinket as the same object.
     * <p>
     * This is missing model rather than a mis-set weight, the same shape as commander damage: no
     * value of any existing parameter could express "this permanent keeps paying".
     * <p>
     * 0 (default) leaves the evaluator blind to draw engines, as upstream.
     */
    /**
     * DARRELLBEST-FORK: penalty per untapped mana source left over on the bot's OWN main phase.
     * <p>
     * Mana that goes unspent on your own turn is tempo thrown away, and the evaluator had no way to
     * see it: a board with eleven untapped sources and a full hand scored identically to one that
     * had spent everything. Live logs caught the bot passing its own main phase with 13 untapped
     * sources and 4 cards in hand, and 14% of all its idle passes held 4+ mana and 3+ cards.
     * <p>
     * Restricted to the bot's own main phases on purpose. Holding mana on someone else's turn is
     * correct play -- that is how instants and counterspells work -- so penalising untapped mana
     * generally would teach it to tap out into open opposing mana.
     */
    /**
     * DARRELLBEST-FORK: 1 = a losing block must beat NOT blocking, not merely score >= 0.
     * <p>
     * The old acceptance was {@code diffBlockingScore >= 0 || diffBlockingScore > diffNonBlockingScore}.
     * That first clause is the bug: an even trade scores about zero, passes {@code >= 0}, and blocks --
     * without ever comparing against how cheap taking the damage would have been. Reported from a live
     * game: the bot blocked a 3/3 Goblin with its own 3/3 Krenko, throwing away its commander to
     * prevent 3 damage at a life total where 3 damage is close to free.
     * <p>
     * Life is a RESOURCE in commander, not a wall to defend. The life table this evaluator inherits
     * was built for 20-life formats: at 40 life, 3 damage still scores 300 (lifeAboveMultiplier 100),
     * which is the same order as a creature, so even trades keep looking acceptable. Requiring the
     * block to actually beat the alternative fixes the comparison without re-tuning that whole table.
     */
    /**
     * DARRELLBEST-FORK: extra value for having your OWN commander on the battlefield.
     * <p>
     * In most commander decks the commander is the deck -- the engine, the win condition, or the
     * thing every other card supports. The evaluator saw a 3/3 legend and scored it like any 3/3.
     * <p>
     * Deliberately an evaluator term rather than combat-specific logic, because it then applies
     * everywhere the search reasons: it stops the bot trading the commander away in blocks, stops it
     * attacking into a losing trade with it, makes it prefer other sacrifice fodder, and makes
     * protecting it score better than an equivalent-stat permanent. One term, every code path.
     * <p>
     * Distinct from {@link #getCommanderBlockPenalty()}, which is the extra cost of specifically
     * LOSING it in a block, and from {@link #getCommanderDamageWeight()}, which is about the damage
     * clock the bot puts on opponents.
     */
    public int getCommanderPermanentBonus() {
        return commanderPermanentBonus;
    }

    public int getBlockTradeMode() {
        return blockTradeMode;
    }

    /**
     * DARRELLBEST-FORK: extra score charged for losing your OWN commander in a block.
     * <p>
     * A commander that dies is not a creature that dies. It goes back to the command zone and costs
     * 2 more mana every time it is recast, and most commander decks are built so that the commander
     * IS the deck's engine. The permanent score cannot express any of that -- it sees a 3/3.
     */
    public int getCommanderBlockPenalty() {
        return commanderBlockPenalty;
    }

    public int getUnspentManaPenalty() {
        return unspentManaPenalty;
    }

    /**
     * DARRELLBEST-FORK: weight per point of mana value across the permanents the bot controls.
     * <p>
     * Board DEVELOPMENT, distinct from board quality. The existing permanent scores value what a
     * creature does; this values having converted cards into permanents at all, which is the thing
     * a hand full of uncast spells is failing to do. It is also the feature a learner needs to tell
     * "ahead on board" from "ahead on cards" -- the hand-tuned evaluator conflates them.
     */
    public int getDeployedManaValueWeight() {
        return deployedManaValueWeight;
    }

    public int getDrawEngineBonus() {
        return drawEngineBonus;
    }

    // --- card definition ---

    public int getBaseCardValue() {
        return baseCardValue;
    }

    public int getLandBaseMultiplier() {
        return landBaseMultiplier;
    }

    public int getLandPerManaSymbol() {
        return landPerManaSymbol;
    }

    public int getNonLandBaseMultiplier() {
        return nonLandBaseMultiplier;
    }

    public int getManaValuePenaltyPerPip() {
        return manaValuePenaltyPerPip;
    }

    public int getCardPowerToughnessMultiplier() {
        return cardPowerToughnessMultiplier;
    }

    public int getRarityMultiplier() {
        return rarityMultiplier;
    }

    // --- permanents ---

    public int getPermanentOnBattlefieldBonus() {
        return permanentOnBattlefieldBonus;
    }

    public int getEquipmentPermanentBonus() {
        return equipmentPermanentBonus;
    }

    public int getChargeCounterScore() {
        return chargeCounterScore;
    }

    public int getLevelCounterScore() {
        return levelCounterScore;
    }

    /** Positive: it is SUBTRACTED per point of marked damage. */
    public int getDamageMarkedPenalty() {
        return damageMarkedPenalty;
    }

    public int getCreaturePowerMultiplier() {
        return creaturePowerMultiplier;
    }

    public int getCreatureToughnessMultiplier() {
        return creatureToughnessMultiplier;
    }

    /** Added to a creature's (non-negative) power before scaling its ability score. */
    public int getAbilityScorePowerOffset() {
        return abilityScorePowerOffset;
    }

    /** Integer divisor applied to the scaled ability score. Must be non-zero. */
    public int getAbilityScoreDivisor() {
        return abilityScoreDivisor;
    }

    public int getAttachedEnchantmentOutcomeMultiplier() {
        return attachedEnchantmentOutcomeMultiplier;
    }

    public int getAttachedEquipmentOutcomeMultiplier() {
        return attachedEquipmentOutcomeMultiplier;
    }

    // --- combat / tapped ---

    /** Negative: it is ADDED when a creature cannot attack. */
    public int getCannotAttackPenalty() {
        return cannotAttackPenalty;
    }

    /** Negative: it is ADDED when a creature cannot block. */
    public int getCannotBlockPenalty() {
        return cannotBlockPenalty;
    }

    /** Negative. */
    public int getTappedCreaturePenalty() {
        return tappedCreaturePenalty;
    }

    /** Negative. Means probably no mana available; should stay greater than the passivity penalty. */
    public int getTappedLandPenalty() {
        return tappedLandPenalty;
    }

    /** Negative. */
    public int getTappedOtherPenalty() {
        return tappedOtherPenalty;
    }

    // --- misc penalties ---

    /**
     * Negative: it is ADDED for each detrimental aura this player attached to its OWN permanent, so
     * the AI stops "improving" its board with Brainwash and Demonic Torment.
     */
    public int getDetrimentalOwnAuraPenalty() {
        return detrimentalOwnAuraPenalty;
    }

    @Override
    public String toString() {
        return "CommanderEvalParams{"
                + "lifeScores=" + Arrays.toString(lifeScores)
                + ", lifeAboveMultiplier=" + lifeAboveMultiplier
                + ", handCardScore=" + handCardScore
                + ", commanderDamageWeight=" + commanderDamageWeight
                + ", opponentSelectionMode=" + opponentSelectionMode
                + ", modeSelectionMode=" + modeSelectionMode
                + ", attackAggression=" + attackAggression
                + ", multiplayerAttackSplit=" + multiplayerAttackSplit
                + ", declineLosingManaPayments=" + declineLosingManaPayments
                + ", smartMulligan=" + smartMulligan
                + ", stackObjectWeight=" + stackObjectWeight
                + ", commanderPermanentBonus=" + commanderPermanentBonus
                + ", blockTradeMode=" + blockTradeMode
                + ", commanderBlockPenalty=" + commanderBlockPenalty
                + ", unspentManaPenalty=" + unspentManaPenalty
                + ", deployedManaValueWeight=" + deployedManaValueWeight
                + ", drawEngineBonus=" + drawEngineBonus
                + ", baseCardValue=" + baseCardValue
                + ", landBaseMultiplier=" + landBaseMultiplier
                + ", landPerManaSymbol=" + landPerManaSymbol
                + ", nonLandBaseMultiplier=" + nonLandBaseMultiplier
                + ", manaValuePenaltyPerPip=" + manaValuePenaltyPerPip
                + ", cardPowerToughnessMultiplier=" + cardPowerToughnessMultiplier
                + ", rarityMultiplier=" + rarityMultiplier
                + ", permanentOnBattlefieldBonus=" + permanentOnBattlefieldBonus
                + ", equipmentPermanentBonus=" + equipmentPermanentBonus
                + ", chargeCounterScore=" + chargeCounterScore
                + ", levelCounterScore=" + levelCounterScore
                + ", damageMarkedPenalty=" + damageMarkedPenalty
                + ", creaturePowerMultiplier=" + creaturePowerMultiplier
                + ", creatureToughnessMultiplier=" + creatureToughnessMultiplier
                + ", abilityScorePowerOffset=" + abilityScorePowerOffset
                + ", abilityScoreDivisor=" + abilityScoreDivisor
                + ", attachedEnchantmentOutcomeMultiplier=" + attachedEnchantmentOutcomeMultiplier
                + ", attachedEquipmentOutcomeMultiplier=" + attachedEquipmentOutcomeMultiplier
                + ", cannotAttackPenalty=" + cannotAttackPenalty
                + ", cannotBlockPenalty=" + cannotBlockPenalty
                + ", tappedCreaturePenalty=" + tappedCreaturePenalty
                + ", tappedLandPenalty=" + tappedLandPenalty
                + ", tappedOtherPenalty=" + tappedOtherPenalty
                + ", detrimentalOwnAuraPenalty=" + detrimentalOwnAuraPenalty
                + '}';
    }

    /**
     * Mutable builder for an immutable {@link CommanderEvalParams}. Every field starts at the
     * historical hard-coded value, so {@code builder().build()} is {@link #DEFAULT} and a tuner only
     * has to state what it wants to CHANGE.
     */
    public static final class Builder {

        private int[] lifeScores = {0, 1000, 2000, 3000, 4000, 4500, 5000, 5500, 6000, 6500, 7000,
                7400, 7800, 8200, 8600, 9000, 9200, 9400, 9600, 9800, 10000};
        private int lifeAboveMultiplier = 100;
        private int handCardScore = 5;
        private int commanderDamageWeight = 0;
        private int opponentSelectionMode = 0;
        private int modeSelectionMode = 0;
        private int attackAggression = 0;
        private int multiplayerAttackSplit = 0;
        private int declineLosingManaPayments = 0;
        private int smartMulligan = 0;
        private int stackObjectWeight = 0;
        private int commanderPermanentBonus = 0;
        private int blockTradeMode = 0;
        private int commanderBlockPenalty = 0;
        private int unspentManaPenalty = 0;
        private int deployedManaValueWeight = 0;
        private int drawEngineBonus = 0;
        private int baseCardValue = 3;
        private int landBaseMultiplier = 50;
        private int landPerManaSymbol = 50;
        private int nonLandBaseMultiplier = 100;
        private int manaValuePenaltyPerPip = 20;
        private int cardPowerToughnessMultiplier = 10;
        private int rarityMultiplier = 30;
        private int permanentOnBattlefieldBonus = 300;
        private int equipmentPermanentBonus = 100;
        private int chargeCounterScore = 30;
        private int levelCounterScore = 30;
        private int damageMarkedPenalty = 2;
        private int creaturePowerMultiplier = 300;
        private int creatureToughnessMultiplier = 200;
        private int abilityScorePowerOffset = 1;
        private int abilityScoreDivisor = 2;
        private int attachedEnchantmentOutcomeMultiplier = 100;
        private int attachedEquipmentOutcomeMultiplier = 50;
        private int cannotAttackPenalty = -100;
        private int cannotBlockPenalty = -30;
        private int tappedCreaturePenalty = -100;
        private int tappedLandPenalty = -20;
        private int tappedOtherPenalty = -2;
        private int detrimentalOwnAuraPenalty = -1000;

        private Builder() {
        }

        /**
         * @param lifeScores score per life total, index 0..n. Copied, so the caller may reuse the
         *                   array. Must be non-empty; its length defines
         *                   {@link CommanderEvalParams#getMaxTabulatedLife()}.
         */
        public Builder lifeScores(int... lifeScores) {
            if (lifeScores == null || lifeScores.length == 0) {
                throw new IllegalArgumentException("lifeScores must contain at least one entry");
            }
            this.lifeScores = Arrays.copyOf(lifeScores, lifeScores.length);
            return this;
        }

        public Builder lifeAboveMultiplier(int v) {
            this.lifeAboveMultiplier = v;
            return this;
        }

        public Builder commanderPermanentBonus(int v) {
            this.commanderPermanentBonus = v;
            return this;
        }

        public Builder blockTradeMode(int v) {
            this.blockTradeMode = v;
            return this;
        }

        public Builder commanderBlockPenalty(int v) {
            this.commanderBlockPenalty = v;
            return this;
        }

        public Builder unspentManaPenalty(int v) {
            this.unspentManaPenalty = v;
            return this;
        }

        public Builder deployedManaValueWeight(int v) {
            this.deployedManaValueWeight = v;
            return this;
        }

        public Builder drawEngineBonus(int v) {
            this.drawEngineBonus = v;
            return this;
        }

        public Builder stackObjectWeight(int v) {
            this.stackObjectWeight = v;
            return this;
        }

        public Builder smartMulligan(int v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("smartMulligan must be 0 or 1, got " + v);
            }
            this.smartMulligan = v;
            return this;
        }

        public Builder declineLosingManaPayments(int v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("declineLosingManaPayments must be 0 or 1, got " + v);
            }
            this.declineLosingManaPayments = v;
            return this;
        }

        public Builder multiplayerAttackSplit(int v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("multiplayerAttackSplit must be 0 (all on one opponent) or 1 (split), got " + v);
            }
            this.multiplayerAttackSplit = v;
            return this;
        }

        public Builder attackAggression(int v) {
            if (v < 0 || v > 2) {
                throw new IllegalArgumentException("attackAggression must be 0 (safe only), 1 (also go wide) or 2 (also favourable trades), got " + v);
            }
            this.attackAggression = v;
            return this;
        }

        public Builder modeSelectionMode(int v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("modeSelectionMode must be 0 (first legal) or 1 (evaluated), got " + v);
            }
            this.modeSelectionMode = v;
            return this;
        }

        public Builder opponentSelectionMode(int v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("opponentSelectionMode must be 0 (first) or 1 (most threatening), got " + v);
            }
            this.opponentSelectionMode = v;
            return this;
        }

        public Builder commanderDamageWeight(int v) {
            this.commanderDamageWeight = v;
            return this;
        }

        public Builder handCardScore(int v) {
            this.handCardScore = v;
            return this;
        }

        public Builder baseCardValue(int v) {
            this.baseCardValue = v;
            return this;
        }

        public Builder landBaseMultiplier(int v) {
            this.landBaseMultiplier = v;
            return this;
        }

        public Builder landPerManaSymbol(int v) {
            this.landPerManaSymbol = v;
            return this;
        }

        public Builder nonLandBaseMultiplier(int v) {
            this.nonLandBaseMultiplier = v;
            return this;
        }

        public Builder manaValuePenaltyPerPip(int v) {
            this.manaValuePenaltyPerPip = v;
            return this;
        }

        public Builder cardPowerToughnessMultiplier(int v) {
            this.cardPowerToughnessMultiplier = v;
            return this;
        }

        public Builder rarityMultiplier(int v) {
            this.rarityMultiplier = v;
            return this;
        }

        public Builder permanentOnBattlefieldBonus(int v) {
            this.permanentOnBattlefieldBonus = v;
            return this;
        }

        public Builder equipmentPermanentBonus(int v) {
            this.equipmentPermanentBonus = v;
            return this;
        }

        public Builder chargeCounterScore(int v) {
            this.chargeCounterScore = v;
            return this;
        }

        public Builder levelCounterScore(int v) {
            this.levelCounterScore = v;
            return this;
        }

        public Builder damageMarkedPenalty(int v) {
            this.damageMarkedPenalty = v;
            return this;
        }

        public Builder creaturePowerMultiplier(int v) {
            this.creaturePowerMultiplier = v;
            return this;
        }

        public Builder creatureToughnessMultiplier(int v) {
            this.creatureToughnessMultiplier = v;
            return this;
        }

        public Builder abilityScorePowerOffset(int v) {
            this.abilityScorePowerOffset = v;
            return this;
        }

        /** @param v must be non-zero -- it is an integer divisor on the evaluator's hot path */
        public Builder abilityScoreDivisor(int v) {
            if (v == 0) {
                throw new IllegalArgumentException("abilityScoreDivisor must be non-zero");
            }
            this.abilityScoreDivisor = v;
            return this;
        }

        public Builder attachedEnchantmentOutcomeMultiplier(int v) {
            this.attachedEnchantmentOutcomeMultiplier = v;
            return this;
        }

        public Builder attachedEquipmentOutcomeMultiplier(int v) {
            this.attachedEquipmentOutcomeMultiplier = v;
            return this;
        }

        public Builder cannotAttackPenalty(int v) {
            this.cannotAttackPenalty = v;
            return this;
        }

        public Builder cannotBlockPenalty(int v) {
            this.cannotBlockPenalty = v;
            return this;
        }

        public Builder tappedCreaturePenalty(int v) {
            this.tappedCreaturePenalty = v;
            return this;
        }

        public Builder tappedLandPenalty(int v) {
            this.tappedLandPenalty = v;
            return this;
        }

        public Builder tappedOtherPenalty(int v) {
            this.tappedOtherPenalty = v;
            return this;
        }

        public Builder detrimentalOwnAuraPenalty(int v) {
            this.detrimentalOwnAuraPenalty = v;
            return this;
        }

        public CommanderEvalParams build() {
            return new CommanderEvalParams(this);
        }
    }
}
