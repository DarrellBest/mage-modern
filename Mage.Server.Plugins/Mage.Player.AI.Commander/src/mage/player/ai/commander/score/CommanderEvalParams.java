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

    // --- life ---
    private final int[] lifeScores;
    private final int lifeAboveMultiplier;

    // --- hand ---
    private final int handCardScore;
    private final int commanderDamageWeight;

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
