package mage.cards.c;

import mage.MageInt;
import mage.abilities.TriggeredAbilityImpl;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.events.GameEvent;

import java.util.UUID;

public final class ChekovAndMcCoy extends CardImpl {

    public ChekovAndMcCoy(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{G}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.PILOT);
        this.subtype.add(SubType.DOCTOR);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Whenever you put one or more +1/+1 counters on Chekov and McCoy, you may draw that many cards. Do this only once each turn.
        this.addAbility(new ChekovAndMcCoyTriggeredAbility());
    }

    private ChekovAndMcCoy(final ChekovAndMcCoy card) {
        super(card);
    }

    @Override
    public ChekovAndMcCoy copy() {
        return new ChekovAndMcCoy(this);
    }
}

class ChekovAndMcCoyTriggeredAbility extends TriggeredAbilityImpl {

    ChekovAndMcCoyTriggeredAbility() {
        super(Zone.BATTLEFIELD, new DrawCardSourceControllerEffect(1), true);
        setTriggerPhrase("Whenever you put one or more +1/+1 counters on {this}, ");
        setDoOnlyOnceEachTurn(true);
    }

    private ChekovAndMcCoyTriggeredAbility(final ChekovAndMcCoyTriggeredAbility ability) {
        super(ability);
    }

    @Override
    public ChekovAndMcCoyTriggeredAbility copy() {
        return new ChekovAndMcCoyTriggeredAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.COUNTERS_ADDED;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        if (!event.getTargetId().equals(getSourceId()) || !isControlledBy(event.getPlayerId())
                || !CounterType.P1P1.getName().equals(event.getData()) || event.getAmount() < 1) {
            return false;
        }
        getEffects().clear();
        addEffect(new DrawCardSourceControllerEffect(event.getAmount()).setText("you may draw that many cards"));
        return true;
    }
}
