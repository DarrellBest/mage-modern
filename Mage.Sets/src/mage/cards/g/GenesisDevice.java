package mage.cards.g;

import mage.abilities.Ability;
import mage.abilities.common.ActivateAsSorceryActivatedAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.costs.common.SacrificeSourceCost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.search.SearchLibraryPutInPlayEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.filter.StaticFilters;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetPermanent;
import mage.target.common.TargetCardInLibrary;

import java.util.UUID;

public final class GenesisDevice extends CardImpl {

    private static final FilterCard basicOrPlanet = new FilterCard("basic land card or Planet card");

    static {
        basicOrPlanet.add(Predicates.or(
                Predicates.and(SuperType.BASIC.getPredicate(), CardType.LAND.getPredicate()),
                SubType.PLANET.getPredicate()
        ));
    }

    public GenesisDevice(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{2}{G}");

        this.supertype.add(SuperType.LEGENDARY);

        // When Genesis Device enters, search your library for a basic land card or Planet card, put it onto the battlefield tapped, then shuffle.
        this.addAbility(new EntersBattlefieldTriggeredAbility(
                new SearchLibraryPutInPlayEffect(new TargetCardInLibrary(basicOrPlanet), true)
        ));

        // {3}{G}{G}, {T}, Sacrifice Genesis Device: Sacrifice any number of lands you control, then search your library
        // for up to that many land cards, put them onto the battlefield tapped, then shuffle. Activate only as a sorcery.
        Ability ability = new ActivateAsSorceryActivatedAbility(new GenesisDeviceEffect(), new ManaCostsImpl<>("{3}{G}{G}"));
        ability.addCost(new TapSourceCost());
        ability.addCost(new SacrificeSourceCost());
        this.addAbility(ability);
    }

    private GenesisDevice(final GenesisDevice card) {
        super(card);
    }

    @Override
    public GenesisDevice copy() {
        return new GenesisDevice(this);
    }
}

class GenesisDeviceEffect extends OneShotEffect {

    GenesisDeviceEffect() {
        super(Outcome.PutLandInPlay);
        staticText = "sacrifice any number of lands you control, then search your library for up to that many land "
                + "cards, put them onto the battlefield tapped, then shuffle";
    }

    private GenesisDeviceEffect(final GenesisDeviceEffect effect) {
        super(effect);
    }

    @Override
    public GenesisDeviceEffect copy() {
        return new GenesisDeviceEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        TargetPermanent toSacrifice = new TargetPermanent(0, Integer.MAX_VALUE,
                StaticFilters.FILTER_CONTROLLED_PERMANENT_LANDS, true);
        controller.choose(Outcome.Sacrifice, toSacrifice, source, game);
        int sacrificed = 0;
        for (UUID id : toSacrifice.getTargets()) {
            Permanent land = game.getPermanent(id);
            if (land != null && land.sacrifice(source, game)) {
                sacrificed++;
            }
        }
        if (sacrificed > 0) {
            TargetCardInLibrary target = new TargetCardInLibrary(0, sacrificed, StaticFilters.FILTER_CARD_LANDS);
            controller.searchLibrary(target, source, game);
            controller.moveCards(new CardsImpl(target.getTargets()).getCards(game), Zone.BATTLEFIELD, source, game,
                    true, false, false, null);
        }
        controller.shuffleLibrary(source, game);
        return true;
    }
}
