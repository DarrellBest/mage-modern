package mage.cards.t;

import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.combat.CantBeBlockedTargetEffect;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.common.TargetCreaturePermanent;

import java.util.UUID;

public final class TranswarpHub extends CardImpl {

    public TranswarpHub(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{2}{U}");

        this.subtype.add(SubType.SPACECRAFT);

        // When this Spacecraft enters, mill five cards. You may put up to two artifact cards from among them into your hand.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new TranswarpHubEffect()));

        // Station
        this.addAbility(new StationAbility());

        // STATION 3+
        // {T}: Target creature can't be blocked this turn.
        Ability ability = new SimpleActivatedAbility(new CantBeBlockedTargetEffect(Duration.EndOfTurn), new TapSourceCost());
        ability.addTarget(new TargetCreaturePermanent());
        this.addAbility(new StationLevelAbility(3).withLevelAbility(ability));
    }

    private TranswarpHub(final TranswarpHub card) {
        super(card);
    }

    @Override
    public TranswarpHub copy() {
        return new TranswarpHub(this);
    }
}

class TranswarpHubEffect extends OneShotEffect {

    TranswarpHubEffect() {
        super(Outcome.ReturnToHand);
        staticText = "mill five cards. You may put up to two artifact cards from among them into your hand";
    }

    private TranswarpHubEffect(final TranswarpHubEffect effect) {
        super(effect);
    }

    @Override
    public TranswarpHubEffect copy() {
        return new TranswarpHubEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        Cards milled = controller.millCards(5, source, game);
        milled.retainZone(Zone.GRAVEYARD, game);
        TargetCard target = new TargetCard(0, 2, Zone.GRAVEYARD, StaticFilters.FILTER_CARD_ARTIFACT);
        target.withNotTarget(true);
        if (milled.count(StaticFilters.FILTER_CARD_ARTIFACT, game) > 0) {
            controller.choose(Outcome.ReturnToHand, milled, target, source, game);
            controller.moveCards(new mage.cards.CardsImpl(target.getTargets()), Zone.HAND, source, game);
        }
        return true;
    }
}
