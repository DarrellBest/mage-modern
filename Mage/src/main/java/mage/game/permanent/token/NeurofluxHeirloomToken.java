package mage.game.permanent.token;

import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.continuous.BoostEquippedEffect;
import mage.abilities.keyword.EquipAbility;
import mage.constants.CardType;
import mage.constants.SubType;

/**
 * Neuroflux Heirloom: colorless Equipment artifact token with "Equipped creature gets +1/+1" and equip {2}
 * (Gwyndala, the Progeny). DARRELLBEST-FORK.
 */
public final class NeurofluxHeirloomToken extends TokenImpl {

    public NeurofluxHeirloomToken() {
        super("Neuroflux Heirloom", "colorless Equipment artifact token named Neuroflux Heirloom with "
                + "\"Equipped creature gets +1/+1\" and equip {2}");
        cardType.add(CardType.ARTIFACT);
        subtype.add(SubType.EQUIPMENT);
        this.addAbility(new SimpleStaticAbility(new BoostEquippedEffect(1, 1)));
        this.addAbility(new EquipAbility(2));
    }

    private NeurofluxHeirloomToken(final NeurofluxHeirloomToken token) {
        super(token);
    }

    @Override
    public NeurofluxHeirloomToken copy() {
        return new NeurofluxHeirloomToken(this);
    }
}
