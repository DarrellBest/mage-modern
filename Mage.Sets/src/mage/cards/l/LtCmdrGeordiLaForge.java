package mage.cards.l;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.util.CardUtil;

import java.util.UUID;

public final class LtCmdrGeordiLaForge extends CardImpl {

    public LtCmdrGeordiLaForge(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(3);
        this.toughness = new MageInt(2);

        // If you would put one or more charge counters on an artifact you control, put twice that many charge counters on that artifact instead.
        this.addAbility(new SimpleStaticAbility(new LtCmdrGeordiLaForgeEffect()));
    }

    private LtCmdrGeordiLaForge(final LtCmdrGeordiLaForge card) {
        super(card);
    }

    @Override
    public LtCmdrGeordiLaForge copy() {
        return new LtCmdrGeordiLaForge(this);
    }
}

class LtCmdrGeordiLaForgeEffect extends ReplacementEffectImpl {

    LtCmdrGeordiLaForgeEffect() {
        super(Duration.WhileOnBattlefield, Outcome.BoostCreature, false);
        staticText = "if you would put one or more charge counters on an artifact you control, "
                + "put twice that many charge counters on that artifact instead";
    }

    private LtCmdrGeordiLaForgeEffect(final LtCmdrGeordiLaForgeEffect effect) {
        super(effect);
    }

    @Override
    public LtCmdrGeordiLaForgeEffect copy() {
        return new LtCmdrGeordiLaForgeEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ADD_COUNTERS;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        // "if you would put": you're the one putting them
        if (!CounterType.CHARGE.getName().equals(event.getData()) || event.getAmount() < 1
                || !source.isControlledBy(event.getPlayerId())) {
            return false;
        }
        Permanent permanent = game.getPermanent(event.getTargetId());
        if (permanent == null) {
            permanent = game.getPermanentEntering(event.getTargetId());
        }
        return permanent != null && permanent.isControlledBy(source.getControllerId()) && permanent.isArtifact(game);
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        event.setAmountForCounters(CardUtil.overflowMultiply(event.getAmount(), 2), true);
        return false;
    }
}
