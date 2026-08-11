package mage.player.ai.kanna;

/**
 * Immutable snapshot of the facts {@link ActionRanker} scores and reports for one
 * catalog entry.
 * <p>
 * Scoring and rendering run on this rather than on {@code ActivatedAbility}/{@code
 * Game} directly so they are pure functions of plain data -- no engine callbacks,
 * therefore unit-testable against hand-constructed values instead of a live game.
 * Same split as {@code CreatureView}/{@code CombatEvaluator}: a data class carrying
 * only what the rules already decided, and a separate extraction step (in {@code
 * ActionRanker}) that pulls it out of a live {@code Ability}/{@code Game}.
 *
 * @author Darrell Best
 */
public final class ActionFacts {

    public enum Category {
        /** A land waiting to be played this turn. */
        LAND,
        /** A spell being cast that becomes a permanent (creature/artifact/enchantment/planeswalker). */
        PERMANENT_SPELL,
        /** A spell being cast that does not stick around (instant/sorcery, or any other non-permanent card type). */
        OTHER_SPELL,
        /**
         * An activated ability of something already on the battlefield (not a card being cast). This
         * ranker has no card-text understanding, so it cannot say anything about what an arbitrary
         * activated ability is worth -- seeing the type/mana-value/P-T of a creature or instant
         * being *cast* is meaningful, but "type: artifact" for an ability belonging to an artifact
         * that is already in play says nothing about what the ability itself does.
         */
        UNCLASSIFIED_ACTIVATED,
        /** PassAbility. */
        PASS
    }

    public final Category category;
    public final String typeLine;
    public final int manaValue;
    public final boolean affordable;
    public final boolean isCreature;
    public final int power;
    public final int toughness;

    public ActionFacts(Category category, String typeLine, int manaValue, boolean affordable,
                        boolean isCreature, int power, int toughness) {
        this.category = category;
        this.typeLine = typeLine;
        this.manaValue = manaValue;
        this.affordable = affordable;
        this.isCreature = isCreature;
        this.power = power;
        this.toughness = toughness;
    }
}
