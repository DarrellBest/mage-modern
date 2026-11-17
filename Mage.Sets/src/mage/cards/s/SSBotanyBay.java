package mage.cards.s;

import mage.abilities.Ability;
import mage.abilities.common.DealsCombatDamageToAPlayerTriggeredAbility;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.ExileZone;
import mage.game.Game;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.common.TargetCardInYourGraveyard;
import mage.util.CardUtil;

import java.util.UUID;

public final class SSBotanyBay extends CardImpl {

    public SSBotanyBay(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{3}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.SPACECRAFT);

        // {T}: Each opponent exiles a card from their graveyard. You may exile a card from your graveyard.
        this.addAbility(new SimpleActivatedAbility(new SSBotanyBayExileEffect(), new TapSourceCost()));

        // Station
        this.addAbility(new StationAbility());

        // STATION 8+
        // Flying
        // Whenever S.S. Botany Bay deals combat damage to a player, return each creature card you own exiled with it to the battlefield.
        // 3/5
        this.addAbility(new StationLevelAbility(8)
                .withLevelAbility(FlyingAbility.getInstance())
                .withLevelAbility(new DealsCombatDamageToAPlayerTriggeredAbility(new SSBotanyBayReturnEffect()))
                .withPT(3, 5));
    }

    private SSBotanyBay(final SSBotanyBay card) {
        super(card);
    }

    @Override
    public SSBotanyBay copy() {
        return new SSBotanyBay(this);
    }
}

class SSBotanyBayExileEffect extends OneShotEffect {

    SSBotanyBayExileEffect() {
        super(Outcome.Exile);
        staticText = "each opponent exiles a card from their graveyard. You may exile a card from your graveyard";
    }

    private SSBotanyBayExileEffect(final SSBotanyBayExileEffect effect) {
        super(effect);
    }

    @Override
    public SSBotanyBayExileEffect copy() {
        return new SSBotanyBayExileEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        UUID exileId = CardUtil.getExileZoneId(game, source);
        String exileName = CardUtil.getSourceName(game, source);
        Cards toExile = new CardsImpl();
        for (UUID opponentId : game.getOpponents(source.getControllerId())) {
            Player opponent = game.getPlayer(opponentId);
            if (opponent == null || opponent.getGraveyard().isEmpty()) {
                continue;
            }
            TargetCard target = new TargetCardInYourGraveyard(1, 1, StaticFilters.FILTER_CARD, true);
            opponent.choose(Outcome.Exile, opponent.getGraveyard(), target, source, game);
            toExile.addAll(target.getTargets());
        }
        if (!controller.getGraveyard().isEmpty()
                && controller.chooseUse(Outcome.Exile, "Exile a card from your graveyard?", source, game)) {
            TargetCard target = new TargetCardInYourGraveyard(1, 1, StaticFilters.FILTER_CARD, true);
            controller.choose(Outcome.Exile, controller.getGraveyard(), target, source, game);
            toExile.addAll(target.getTargets());
        }
        return controller.moveCardsToExile(toExile.getCards(game), source, game, true, exileId, exileName);
    }
}

class SSBotanyBayReturnEffect extends OneShotEffect {

    SSBotanyBayReturnEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "return each creature card you own exiled with it to the battlefield";
    }

    private SSBotanyBayReturnEffect(final SSBotanyBayReturnEffect effect) {
        super(effect);
    }

    @Override
    public SSBotanyBayReturnEffect copy() {
        return new SSBotanyBayReturnEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        ExileZone exile = game.getExile().getExileZone(CardUtil.getExileZoneId(game, source));
        if (controller == null || exile == null) {
            return false;
        }
        Cards cards = new CardsImpl();
        for (Card card : exile.getCards(game)) {
            if (card.isCreature(game) && card.isOwnedBy(controller.getId())) {
                cards.add(card);
            }
        }
        return controller.moveCards(cards, Zone.BATTLEFIELD, source, game);
    }
}
