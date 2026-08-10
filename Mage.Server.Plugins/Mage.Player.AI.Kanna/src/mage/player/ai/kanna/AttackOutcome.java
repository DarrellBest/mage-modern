package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computed result of one attack. Every field is arithmetic, not opinion --
 * the model decides whether the outcome is worth having.
 *
 * @author Darrell Best
 */
public final class AttackOutcome {

    public final boolean attackerDies;
    public final List<String> blockersThatDie;
    public final int damageThrough;
    public final boolean unblocked;
    public final String summary;

    public AttackOutcome(boolean attackerDies, List<String> blockersThatDie,
                         int damageThrough, boolean unblocked, String summary) {
        this.attackerDies = attackerDies;
        this.blockersThatDie = Collections.unmodifiableList(new ArrayList<String>(blockersThatDie));
        this.damageThrough = damageThrough;
        this.unblocked = unblocked;
        this.summary = summary;
    }
}
