package mage.cards.b;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.BecomesTappedSourceTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.abilities.keyword.LifelinkAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.util.CardUtil;

import java.util.UUID;

public final class BradBoimlerEagerEnsign extends CardImpl {

    public BradBoimlerEagerEnsign(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Lifelink
        this.addAbility(LifelinkAbility.getInstance());

        // Whenever Brad Boimler becomes tapped, until end of turn, if one or more counters would be put on a permanent
        // you control, that many plus one of each of those kinds of counters are put on that permanent instead.
        this.addAbility(new BecomesTappedSourceTriggeredAbility(new BradBoimlerEagerEnsignEffect()));
    }

    private BradBoimlerEagerEnsign(final BradBoimlerEagerEnsign card) {
        super(card);
    }

    @Override
    public BradBoimlerEagerEnsign copy() {
        return new BradBoimlerEagerEnsign(this);
    }
}

class BradBoimlerEagerEnsignEffect extends OneShotEffect {

    BradBoimlerEagerEnsignEffect() {
        super(Outcome.BoostCreature);
        staticText = "until end of turn, if one or more counters would be put on a permanent you control, that many "
                + "plus one of each of those kinds of counters are put on that permanent instead";
    }

    private BradBoimlerEagerEnsignEffect(final BradBoimlerEagerEnsignEffect effect) {
        super(effect);
    }

    @Override
    public BradBoimlerEagerEnsignEffect copy() {
        return new BradBoimlerEagerEnsignEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        game.addEffect(new BradBoimlerEagerEnsignReplacementEffect(), source);
        return true;
    }
}

class BradBoimlerEagerEnsignReplacementEffect extends ReplacementEffectImpl {

    BradBoimlerEagerEnsignReplacementEffect() {
        super(Duration.EndOfTurn, Outcome.BoostCreature, false);
    }

    private BradBoimlerEagerEnsignReplacementEffect(final BradBoimlerEagerEnsignReplacementEffect effect) {
        super(effect);
    }

    @Override
    public BradBoimlerEagerEnsignReplacementEffect copy() {
        return new BradBoimlerEagerEnsignReplacementEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ADD_COUNTERS;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        if (event.getAmount() < 1) {
            return false;
        }
        Permanent permanent = game.getPermanent(event.getTargetId());
        if (permanent == null) {
            permanent = game.getPermanentEntering(event.getTargetId());
        }
        return permanent != null && permanent.isControlledBy(source.getControllerId());
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        // ADD_COUNTERS is fired once per kind of counter, so "plus one of each of those kinds" is +1 here
        event.setAmountForCounters(CardUtil.overflowInc(event.getAmount(), 1), true);
        return false;
    }
}
