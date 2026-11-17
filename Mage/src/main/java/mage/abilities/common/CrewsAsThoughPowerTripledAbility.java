package mage.abilities.common;

import mage.abilities.StaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.constants.Zone;

/**
 * Marker: this creature crews Vehicles as though its power were tripled (Sulu, Heroic Helmsman). Read by
 * {@link mage.abilities.keyword.CrewAbility}; the station half lives on the card. DARRELLBEST-FORK.
 */
public class CrewsAsThoughPowerTripledAbility extends StaticAbility {

    public CrewsAsThoughPowerTripledAbility() {
        super(Zone.BATTLEFIELD, new InfoEffect("crews Vehicles as though its power were tripled"));
        this.setRuleVisible(false);
    }

    protected CrewsAsThoughPowerTripledAbility(final CrewsAsThoughPowerTripledAbility ability) {
        super(ability);
    }

    @Override
    public CrewsAsThoughPowerTripledAbility copy() {
        return new CrewsAsThoughPowerTripledAbility(this);
    }
}
