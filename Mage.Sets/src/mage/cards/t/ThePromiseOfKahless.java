package mage.cards.t;

import mage.abilities.Ability;
import mage.abilities.costs.common.SacrificeTargetCost;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.common.FilterControlledPermanent;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ThePromiseOfKahless extends CardImpl {

    public ThePromiseOfKahless(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{4}{B}");

        // You may sacrifice a Klingon or Warrior. If you do, return all other Klingon and Warrior creature cards from
        // your graveyard to the battlefield.
        this.getSpellAbility().addEffect(new ThePromiseOfKahlessEffect());
    }

    private ThePromiseOfKahless(final ThePromiseOfKahless card) {
        super(card);
    }

    @Override
    public ThePromiseOfKahless copy() {
        return new ThePromiseOfKahless(this);
    }
}

class ThePromiseOfKahlessEffect extends OneShotEffect {

    private static final FilterControlledPermanent filter = new FilterControlledPermanent("a Klingon or Warrior");

    static {
        filter.add(Predicates.or(SubType.KLINGON.getPredicate(), SubType.WARRIOR.getPredicate()));
    }

    ThePromiseOfKahlessEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "you may sacrifice a Klingon or Warrior. If you do, return all other Klingon and Warrior "
                + "creature cards from your graveyard to the battlefield";
    }

    private ThePromiseOfKahlessEffect(final ThePromiseOfKahlessEffect effect) {
        super(effect);
    }

    @Override
    public ThePromiseOfKahlessEffect copy() {
        return new ThePromiseOfKahlessEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        SacrificeTargetCost cost = new SacrificeTargetCost(filter);
        if (!cost.canPay(source, source, controller.getId(), game)
                || !controller.chooseUse(Outcome.Sacrifice, "Sacrifice a Klingon or Warrior?", source, game)
                || !cost.pay(source, game, source, controller.getId(), true)) {
            return false;
        }
        Set<UUID> sacrificed = new HashSet<>();
        for (Permanent permanent : cost.getPermanents()) {
            sacrificed.add(permanent.getId());
        }
        Cards toReturn = new CardsImpl();
        for (Card card : controller.getGraveyard().getCards(game)) {
            if (!sacrificed.contains(card.getId()) && card.isCreature(game)
                    && (card.hasSubtype(SubType.KLINGON, game) || card.hasSubtype(SubType.WARRIOR, game))) {
                toReturn.add(card);
            }
        }
        return controller.moveCards(toReturn, Zone.BATTLEFIELD, source, game);
    }
}
