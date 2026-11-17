package mage.cards.e;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.TriggeredAbilityImpl;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.RemoveCounterCost;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.counter.AddCountersSourceEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.filter.common.FilterControlledCreaturePermanent;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class EnsignHarryKimOperations extends CardImpl {

    private static final FilterControlledCreaturePermanent withCounters
            = new FilterControlledCreaturePermanent("creatures you control with +1/+1 counters");

    static {
        withCounters.add(CounterType.P1P1.getPredicate());
    }

    public EnsignHarryKimOperations(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(1);
        this.toughness = new MageInt(2);

        // Whenever another creature you control enters, if it has greater power than Ensign Kim, put a +1/+1 counter on Ensign Kim.
        this.addAbility(new EnsignHarryKimOperationsTriggeredAbility());

        // {2}, Remove two +1/+1 counters from among creatures you control: Draw a card.
        Ability ability = new SimpleActivatedAbility(new DrawCardSourceControllerEffect(1), new GenericManaCost(2));
        ability.addCost(new RemoveCounterCost(new TargetPermanent(1, 2, withCounters, true), CounterType.P1P1, 2)
                .setText("remove two +1/+1 counters from among creatures you control"));
        this.addAbility(ability);
    }

    private EnsignHarryKimOperations(final EnsignHarryKimOperations card) {
        super(card);
    }

    @Override
    public EnsignHarryKimOperations copy() {
        return new EnsignHarryKimOperations(this);
    }
}

class EnsignHarryKimOperationsTriggeredAbility extends TriggeredAbilityImpl {

    EnsignHarryKimOperationsTriggeredAbility() {
        super(Zone.BATTLEFIELD, new AddCountersSourceEffect(CounterType.P1P1.createInstance()));
        setTriggerPhrase("Whenever another creature you control enters, if it has greater power than {this}, ");
    }

    private EnsignHarryKimOperationsTriggeredAbility(final EnsignHarryKimOperationsTriggeredAbility ability) {
        super(ability);
    }

    @Override
    public EnsignHarryKimOperationsTriggeredAbility copy() {
        return new EnsignHarryKimOperationsTriggeredAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ENTERS_THE_BATTLEFIELD;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        if (event.getTargetId().equals(getSourceId())) {
            return false;
        }
        Permanent entering = game.getPermanent(event.getTargetId());
        if (entering == null || !entering.isCreature(game) || !entering.isControlledBy(getControllerId())) {
            return false;
        }
        getEffects().setValue("enteringId", entering.getId());
        return hasGreaterPower(entering.getId(), game);
    }

    @Override
    public boolean checkInterveningIfClause(Game game) {
        Object id = getEffects().get(0).getValue("enteringId");
        return id instanceof UUID && hasGreaterPower((UUID) id, game);
    }

    private boolean hasGreaterPower(UUID enteringId, Game game) {
        Permanent entering = game.getPermanentOrLKIBattlefield(enteringId);
        Permanent kim = game.getPermanentOrLKIBattlefield(getSourceId());
        return entering != null && kim != null && entering.getPower().getValue() > kim.getPower().getValue();
    }
}
