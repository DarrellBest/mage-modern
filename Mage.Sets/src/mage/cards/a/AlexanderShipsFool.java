package mage.cards.a;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.HasteAbility;
import mage.abilities.keyword.VigilanceAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.players.Player;
import mage.util.RandomUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AlexanderShipsFool extends CardImpl {

    public AlexanderShipsFool(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{R}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.KLINGON);
        this.subtype.add(SubType.HUMAN);
        this.power = new MageInt(3);
        this.toughness = new MageInt(5);

        // Vigilance, haste
        this.addAbility(VigilanceAbility.getInstance());
        this.addAbility(HasteAbility.getInstance());

        // {T}: Each player reveals the top card of their library, then draws a card. Alexander deals X damage to a
        // random opponent, where X is the greatest mana value among cards revealed this way.
        this.addAbility(new SimpleActivatedAbility(new AlexanderShipsFoolEffect(), new TapSourceCost()));
    }

    private AlexanderShipsFool(final AlexanderShipsFool card) {
        super(card);
    }

    @Override
    public AlexanderShipsFool copy() {
        return new AlexanderShipsFool(this);
    }
}

class AlexanderShipsFoolEffect extends OneShotEffect {

    AlexanderShipsFoolEffect() {
        super(Outcome.Damage);
        staticText = "each player reveals the top card of their library, then draws a card. {this} deals X damage to "
                + "a random opponent, where X is the greatest mana value among cards revealed this way";
    }

    private AlexanderShipsFoolEffect(final AlexanderShipsFoolEffect effect) {
        super(effect);
    }

    @Override
    public AlexanderShipsFoolEffect copy() {
        return new AlexanderShipsFoolEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        int greatest = 0;
        for (UUID playerId : game.getState().getPlayersInRange(source.getControllerId(), game)) {
            Player player = game.getPlayer(playerId);
            Card top = player == null ? null : player.getLibrary().getFromTop(game);
            if (top != null) {
                player.revealCards(source, new CardsImpl(top), game);
                greatest = Math.max(greatest, top.getManaValue());
            }
        }
        for (UUID playerId : game.getState().getPlayersInRange(source.getControllerId(), game)) {
            Player player = game.getPlayer(playerId);
            if (player != null) {
                player.drawCards(1, source, game);
            }
        }
        List<UUID> opponents = new ArrayList<>(game.getOpponents(source.getControllerId()));
        if (greatest > 0 && !opponents.isEmpty()) {
            Player opponent = game.getPlayer(opponents.get(RandomUtil.nextInt(opponents.size())));
            if (opponent != null) {
                opponent.damage(greatest, source.getSourceId(), source, game);
            }
        }
        return true;
    }
}
