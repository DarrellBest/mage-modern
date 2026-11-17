package mage.game.permanent.token;

import mage.MageInt;
import mage.abilities.keyword.FlyingAbility;
import mage.constants.CardType;
import mage.constants.SubType;

/**
 * 4/3 colorless Spacecraft Illusion artifact creature token with flying (U.S.S. Stargazer). DARRELLBEST-FORK.
 */
public final class SpacecraftIllusionToken extends TokenImpl {

    public SpacecraftIllusionToken() {
        super("Spacecraft Illusion Token", "4/3 colorless Spacecraft Illusion artifact creature token with flying");
        cardType.add(CardType.ARTIFACT);
        cardType.add(CardType.CREATURE);
        subtype.add(SubType.SPACECRAFT);
        subtype.add(SubType.ILLUSION);
        power = new MageInt(4);
        toughness = new MageInt(3);
        this.addAbility(FlyingAbility.getInstance());
    }

    private SpacecraftIllusionToken(final SpacecraftIllusionToken token) {
        super(token);
    }

    @Override
    public SpacecraftIllusionToken copy() {
        return new SpacecraftIllusionToken(this);
    }
}
