package mage.player.ai.kanna;

import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.DoubleStrikeAbility;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.MenaceAbility;
import mage.abilities.keyword.ReachAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.game.Game;
import mage.game.permanent.Permanent;

/**
 * Immutable snapshot of the creature facts that decide combat.
 * <p>
 * Combat math runs on this rather than on Permanent so it is a pure function of
 * plain data: no Game, no engine callbacks, and therefore unit-testable against
 * hand-constructed board states instead of a live game.
 *
 * @author Darrell Best
 */
public final class CreatureView {

    public final String id;
    public final String name;
    public final int power;
    public final int toughness;
    public final boolean flying;
    public final boolean reach;
    public final boolean menace;
    public final boolean deathtouch;
    public final boolean firstStrike;
    public final boolean doubleStrike;
    public final boolean trample;
    public final boolean tapped;

    public CreatureView(String id, String name, int power, int toughness,
                        boolean flying, boolean reach, boolean menace, boolean deathtouch,
                        boolean firstStrike, boolean doubleStrike, boolean trample, boolean tapped) {
        this.id = id;
        this.name = name;
        this.power = power;
        this.toughness = toughness;
        this.flying = flying;
        this.reach = reach;
        this.menace = menace;
        this.deathtouch = deathtouch;
        this.firstStrike = firstStrike;
        this.doubleStrike = doubleStrike;
        this.trample = trample;
        this.tapped = tapped;
    }

    public static CreatureView from(String id, Permanent permanent, Game game) {
        return new CreatureView(
                id,
                permanent.getName(),
                permanent.getPower().getValue(),
                permanent.getToughness().getValue(),
                permanent.getAbilities(game).containsClass(FlyingAbility.class),
                permanent.getAbilities(game).containsClass(ReachAbility.class),
                permanent.getAbilities(game).containsClass(MenaceAbility.class),
                permanent.getAbilities(game).containsClass(DeathtouchAbility.class),
                permanent.getAbilities(game).containsClass(FirstStrikeAbility.class),
                permanent.getAbilities(game).containsClass(DoubleStrikeAbility.class),
                permanent.getAbilities(game).containsClass(TrampleAbility.class),
                permanent.isTapped()
        );
    }

    /**
     * Compact "Name (2/2) [Flying, Deathtouch]" rendering for prompts.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" (").append(power).append('/').append(toughness).append(')');
        StringBuilder kw = new StringBuilder();
        appendKeyword(kw, flying, "Flying");
        appendKeyword(kw, reach, "Reach");
        appendKeyword(kw, menace, "Menace");
        appendKeyword(kw, deathtouch, "Deathtouch");
        appendKeyword(kw, firstStrike, "First Strike");
        appendKeyword(kw, doubleStrike, "Double Strike");
        appendKeyword(kw, trample, "Trample");
        if (kw.length() > 0) {
            sb.append(" [").append(kw).append(']');
        }
        return sb.toString();
    }

    private static void appendKeyword(StringBuilder sb, boolean present, String label) {
        if (present) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label);
        }
    }
}
