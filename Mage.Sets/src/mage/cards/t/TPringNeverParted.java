package mage.cards.t;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.DelayedTriggeredAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetPermanent;
import mage.target.common.TargetOpponentsCreaturePermanent;
import mage.target.targetpointer.FixedTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TPringNeverParted extends CardImpl {

    public TPringNeverParted(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{G}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.VULCAN);
        this.subtype.add(SubType.CITIZEN);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Kal-if-fee -- When T'Pring enters, choose another target creature you control and target creature an
        // opponent controls. You may have the chosen creatures fight each other. Whenever a creature that fought this
        // way dies this turn, you draw a card.
        Ability ability = new EntersBattlefieldTriggeredAbility(new TPringNeverPartedEffect());
        ability.addTarget(new TargetPermanent(StaticFilters.FILTER_ANOTHER_CREATURE_YOU_CONTROL));
        ability.addTarget(new TargetOpponentsCreaturePermanent());
        this.addAbility(ability.withFlavorWord("Kal-if-fee"));
    }

    private TPringNeverParted(final TPringNeverParted card) {
        super(card);
    }

    @Override
    public TPringNeverParted copy() {
        return new TPringNeverParted(this);
    }
}

class TPringNeverPartedEffect extends OneShotEffect {

    TPringNeverPartedEffect() {
        super(Outcome.Damage);
        staticText = "choose another target creature you control and target creature an opponent controls. You may "
                + "have the chosen creatures fight each other. Whenever a creature that fought this way dies this "
                + "turn, you draw a card";
    }

    private TPringNeverPartedEffect(final TPringNeverPartedEffect effect) {
        super(effect);
    }

    @Override
    public TPringNeverPartedEffect copy() {
        return new TPringNeverPartedEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Permanent yours = game.getPermanent(source.getTargets().get(0).getFirstTarget());
        Permanent theirs = game.getPermanent(source.getTargets().get(1).getFirstTarget());
        if (controller == null || yours == null || theirs == null
                || !controller.chooseUse(Outcome.Damage, "Have them fight?", source, game)) {
            return false;
        }
        // remember both before damage: either may die from the fight itself
        List<FixedTarget> fought = new ArrayList<>();
        fought.add(new FixedTarget(yours, game));
        fought.add(new FixedTarget(theirs, game));
        game.addDelayedTriggeredAbility(new TPringNeverPartedDiesAbility(fought), source);
        yours.fight(theirs, source, game);
        return true;
    }
}

class TPringNeverPartedDiesAbility extends DelayedTriggeredAbility {

    private final List<FixedTarget> fought;

    TPringNeverPartedDiesAbility(List<FixedTarget> fought) {
        super(new DrawCardSourceControllerEffect(1), Duration.EndOfTurn, false, false);
        this.fought = fought;
    }

    private TPringNeverPartedDiesAbility(final TPringNeverPartedDiesAbility ability) {
        super(ability);
        this.fought = new ArrayList<>(ability.fought);
    }

    @Override
    public TPringNeverPartedDiesAbility copy() {
        return new TPringNeverPartedDiesAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ZONE_CHANGE;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        ZoneChangeEvent zce = (ZoneChangeEvent) event;
        if (!zce.isDiesEvent() || zce.getTarget() == null) {
            return false;
        }
        for (FixedTarget target : fought) {
            if (target.getTarget().equals(zce.getTargetId())
                    && zce.getTarget().getZoneChangeCounter(game) == target.getZoneChangeCounter()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getRule() {
        return "Whenever a creature that fought this way dies this turn, you draw a card.";
    }
}
