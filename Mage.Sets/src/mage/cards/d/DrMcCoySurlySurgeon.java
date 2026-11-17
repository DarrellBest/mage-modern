package mage.cards.d;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.abilities.effects.common.counter.AddCountersTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.target.common.TargetControlledCreaturePermanent;

import java.util.UUID;

public final class DrMcCoySurlySurgeon extends CardImpl {

    public DrMcCoySurlySurgeon(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{W}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.DOCTOR);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // When Dr. McCoy enters, put a +1/+1 counter on another target creature you control.
        Ability ability = new EntersBattlefieldTriggeredAbility(new AddCountersTargetEffect(CounterType.P1P1.createInstance()));
        ability.addTarget(new mage.target.TargetPermanent(StaticFilters.FILTER_ANOTHER_CREATURE_YOU_CONTROL));
        this.addAbility(ability);

        // If another creature you control with a +1/+1 counter on it would be destroyed, instead remove all +1/+1
        // counters from it and all damage already dealt to it is healed.
        this.addAbility(new SimpleStaticAbility(new DrMcCoySurlySurgeonEffect()));
    }

    private DrMcCoySurlySurgeon(final DrMcCoySurlySurgeon card) {
        super(card);
    }

    @Override
    public DrMcCoySurlySurgeon copy() {
        return new DrMcCoySurlySurgeon(this);
    }
}

class DrMcCoySurlySurgeonEffect extends ReplacementEffectImpl {

    DrMcCoySurlySurgeonEffect() {
        super(Duration.WhileOnBattlefield, Outcome.Regenerate);
        staticText = "If another creature you control with a +1/+1 counter on it would be destroyed, instead remove "
                + "all +1/+1 counters from it and all damage already dealt to it is healed";
    }

    private DrMcCoySurlySurgeonEffect(final DrMcCoySurlySurgeonEffect effect) {
        super(effect);
    }

    @Override
    public DrMcCoySurlySurgeonEffect copy() {
        return new DrMcCoySurlySurgeonEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.DESTROY_PERMANENT;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        Permanent permanent = game.getPermanent(event.getTargetId());
        return permanent != null && !permanent.getId().equals(source.getSourceId())
                && permanent.isCreature(game) && permanent.isControlledBy(source.getControllerId())
                && permanent.getCounters(game).getCount(CounterType.P1P1) > 0;
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        Permanent permanent = game.getPermanent(event.getTargetId());
        if (permanent != null) {
            permanent.removeCounters(CounterType.P1P1.getName(),
                    permanent.getCounters(game).getCount(CounterType.P1P1), source, game);
            permanent.removeAllDamage(game);
        }
        return true;
    }
}
