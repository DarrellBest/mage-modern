package mage.cards.j;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.dynamicvalue.common.FederationCount;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.abilities.keyword.FlyingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AbilityWord;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.events.EntersTheBattlefieldEvent;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;

import java.util.UUID;

public final class JeanLucMonCapitaine extends CardImpl {

    public JeanLucMonCapitaine(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}{R}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(5);
        this.toughness = new MageInt(3);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // Federation -- Faeries you control enter with an additional +1/+1 counter on them for each creature type
        // among non-Borg creatures you already control.
        this.addAbility(new SimpleStaticAbility(new JeanLucMonCapitaineEffect())
                .setAbilityWord(AbilityWord.FEDERATION).addHint(FederationCount.instance.getHint()));
    }

    private JeanLucMonCapitaine(final JeanLucMonCapitaine card) {
        super(card);
    }

    @Override
    public JeanLucMonCapitaine copy() {
        return new JeanLucMonCapitaine(this);
    }
}

class JeanLucMonCapitaineEffect extends ReplacementEffectImpl {

    JeanLucMonCapitaineEffect() {
        super(Duration.WhileOnBattlefield, Outcome.BoostCreature);
        staticText = "Faeries you control enter with an additional +1/+1 counter on them for each creature type "
                + "among non-Borg creatures you already control";
    }

    private JeanLucMonCapitaineEffect(final JeanLucMonCapitaineEffect effect) {
        super(effect);
    }

    @Override
    public JeanLucMonCapitaineEffect copy() {
        return new JeanLucMonCapitaineEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ENTERS_THE_BATTLEFIELD;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        Permanent permanent = ((EntersTheBattlefieldEvent) event).getTarget();
        return permanent != null && permanent.isControlledBy(source.getControllerId())
                && permanent.hasSubtype(SubType.FAERIE, game);
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        Permanent faerie = ((EntersTheBattlefieldEvent) event).getTarget();
        // "already control": the entering Faerie isn't on the battlefield yet, so it isn't counted
        int types = FederationCount.instance.calculate(game, source, this);
        if (faerie != null && types > 0) {
            faerie.addCounters(CounterType.P1P1.createInstance(types), source.getControllerId(), source, game, event.getAppliedEffects());
        }
        return false;
    }
}
