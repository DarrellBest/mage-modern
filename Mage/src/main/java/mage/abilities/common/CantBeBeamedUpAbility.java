package mage.abilities.common;

import mage.abilities.StaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.constants.Zone;

/**
 * "This permanent can't be beamed up": it can't be the creature you return to its owner's hand to pay a beam me up
 * cost (702.196, Star Trek). Checked by {@link mage.abilities.keyword.BeamMeUpAbility}. DARRELLBEST-FORK.
 */
public class CantBeBeamedUpAbility extends StaticAbility {

    public CantBeBeamedUpAbility() {
        super(Zone.BATTLEFIELD, new InfoEffect("this permanent can't be beamed up"));
        this.setRuleVisible(false); // the granting card prints the rule
    }

    protected CantBeBeamedUpAbility(final CantBeBeamedUpAbility ability) {
        super(ability);
    }

    @Override
    public CantBeBeamedUpAbility copy() {
        return new CantBeBeamedUpAbility(this);
    }
}
