package mage.abilities.common;

import mage.abilities.TriggeredAbilityImpl;
import mage.abilities.effects.Effect;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.GameEvent;

/**
 * "Whenever you face a dilemma, ..." (700.17, Star Trek): you choose one or more modes for a modal spell or
 * ability. It doesn't matter what happens to that spell or ability afterwards. DARRELLBEST-FORK.
 */
public class FaceDilemmaTriggeredAbility extends TriggeredAbilityImpl {

    public FaceDilemmaTriggeredAbility(Effect effect) {
        this(Zone.BATTLEFIELD, effect, false);
    }

    public FaceDilemmaTriggeredAbility(Effect effect, boolean optional) {
        this(Zone.BATTLEFIELD, effect, optional);
    }

    public FaceDilemmaTriggeredAbility(Zone zone, Effect effect, boolean optional) {
        super(zone, effect, optional);
        setTriggerPhrase("Whenever you face a dilemma, ");
    }

    protected FaceDilemmaTriggeredAbility(final FaceDilemmaTriggeredAbility ability) {
        super(ability);
    }

    @Override
    public FaceDilemmaTriggeredAbility copy() {
        return new FaceDilemmaTriggeredAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.FACED_DILEMMA;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        return isControlledBy(event.getPlayerId());
    }
}
