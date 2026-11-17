package mage.cards.p;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.AttacksEachCombatStaticAbility;
import mage.abilities.common.DealsCombatDamageToAPlayerTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.IndestructibleAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.counters.CounterType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.UUID;

public final class PlanetKiller extends CardImpl {

    public PlanetKiller(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{8}");

        this.subtype.add(SubType.CONSTRUCT);
        this.power = new MageInt(9);
        this.toughness = new MageInt(9);

        // Flying, indestructible
        this.addAbility(FlyingAbility.getInstance());
        this.addAbility(IndestructibleAbility.getInstance());

        // This creature attacks each combat if able.
        this.addAbility(new AttacksEachCombatStaticAbility());

        // Whenever this creature deals combat damage to a player, put a +1/+1 counter on it for each land that player controls.
        this.addAbility(new DealsCombatDamageToAPlayerTriggeredAbility(new PlanetKillerEffect(), false, true));
    }

    private PlanetKiller(final PlanetKiller card) {
        super(card);
    }

    @Override
    public PlanetKiller copy() {
        return new PlanetKiller(this);
    }
}

class PlanetKillerEffect extends OneShotEffect {

    PlanetKillerEffect() {
        super(Outcome.BoostCreature);
        staticText = "put a +1/+1 counter on it for each land that player controls";
    }

    private PlanetKillerEffect(final PlanetKillerEffect effect) {
        super(effect);
    }

    @Override
    public PlanetKillerEffect copy() {
        return new PlanetKillerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent permanent = source.getSourcePermanentIfItStillExists(game);
        UUID playerId = getTargetPointer().getFirst(game, source);
        if (permanent == null || playerId == null) {
            return false;
        }
        int lands = game.getBattlefield().countAll(StaticFilters.FILTER_LAND, playerId, game);
        if (lands > 0) {
            permanent.addCounters(CounterType.P1P1.createInstance(lands), source.getControllerId(), source, game);
        }
        return true;
    }
}
