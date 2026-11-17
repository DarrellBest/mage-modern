package mage.cards.k;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.TriggeredAbilityImpl;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.combat.GoadTargetEffect;
import mage.abilities.effects.common.counter.AddCountersTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.target.common.TargetControlledCreaturePermanent;
import mage.target.common.TargetOpponentsCreaturePermanent;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class KangSuspiciousCaptain extends CardImpl {

    public KangSuspiciousCaptain(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.KLINGON);
        this.subtype.add(SubType.WARRIOR);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(4);
        this.toughness = new MageInt(4);

        // When Kang enters, choose target creature an opponent controls. Kang and that creature are goaded for as long as you control Kang.
        Ability ability = new EntersBattlefieldTriggeredAbility(new KangSuspiciousCaptainEffect());
        ability.addTarget(new TargetOpponentsCreaturePermanent());
        this.addAbility(ability);

        // Whenever one or more goaded creatures attack, put a +1/+1 counter on target creature you control.
        this.addAbility(new KangSuspiciousCaptainTriggeredAbility());
    }

    private KangSuspiciousCaptain(final KangSuspiciousCaptain card) {
        super(card);
    }

    @Override
    public KangSuspiciousCaptain copy() {
        return new KangSuspiciousCaptain(this);
    }
}

class KangSuspiciousCaptainEffect extends OneShotEffect {

    KangSuspiciousCaptainEffect() {
        super(Outcome.Detriment);
        staticText = "choose target creature an opponent controls. {this} and that creature are goaded for as long as you control {this}";
    }

    private KangSuspiciousCaptainEffect(final KangSuspiciousCaptainEffect effect) {
        super(effect);
    }

    @Override
    public KangSuspiciousCaptainEffect copy() {
        return new KangSuspiciousCaptainEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent kang = source.getSourcePermanentIfItStillExists(game);
        if (kang == null) {
            return false;
        }
        Permanent other = game.getPermanent(getTargetPointer().getFirst(game, source));
        for (Permanent permanent : new Permanent[]{kang, other}) {
            if (permanent == null) {
                continue;
            }
            GoadTargetEffect goad = new KangGoadEffect(new FixedTarget(kang, game));
            goad.setTargetPointer(new FixedTarget(permanent, game));
            game.addEffect(goad, source);
        }
        return true;
    }
}

/** Goad that lasts for as long as you control Kang (that same Kang object). */
class KangGoadEffect extends GoadTargetEffect {

    private final FixedTarget kang;

    KangGoadEffect(FixedTarget kang) {
        super(Duration.Custom);
        this.kang = kang;
    }

    private KangGoadEffect(final KangGoadEffect effect) {
        super(effect);
        this.kang = effect.kang;
    }

    @Override
    public KangGoadEffect copy() {
        return new KangGoadEffect(this);
    }

    @Override
    public boolean isInactive(Ability source, Game game) {
        Permanent permanent = game.getPermanent(kang.getTarget());
        if (permanent == null || permanent.getZoneChangeCounter(game) != kang.getZoneChangeCounter()
                || !permanent.isControlledBy(source.getControllerId())) {
            discard();
            return true;
        }
        return super.isInactive(source, game);
    }
}

class KangSuspiciousCaptainTriggeredAbility extends TriggeredAbilityImpl {

    KangSuspiciousCaptainTriggeredAbility() {
        super(Zone.BATTLEFIELD, new AddCountersTargetEffect(CounterType.P1P1.createInstance()));
        this.addTarget(new TargetControlledCreaturePermanent());
        setTriggerPhrase("Whenever one or more goaded creatures attack, ");
    }

    private KangSuspiciousCaptainTriggeredAbility(final KangSuspiciousCaptainTriggeredAbility ability) {
        super(ability);
    }

    @Override
    public KangSuspiciousCaptainTriggeredAbility copy() {
        return new KangSuspiciousCaptainTriggeredAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.DECLARED_ATTACKERS;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        for (UUID attackerId : game.getCombat().getAttackers()) {
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker != null && !attacker.getGoadingPlayers().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
