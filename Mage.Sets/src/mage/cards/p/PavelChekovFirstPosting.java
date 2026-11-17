package mage.cards.p;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.players.Player;
import mage.util.CardUtil;

import java.util.UUID;

public final class PavelChekovFirstPosting extends CardImpl {

    public PavelChekovFirstPosting(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{G}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.PILOT);
        this.power = new MageInt(1);
        this.toughness = new MageInt(4);

        // If you would draw a card, you may instead choose land or nonland and reveal cards from the top of your
        // library until you reveal a card of the chosen kind. Put that card into your hand and put all other cards
        // revealed this way on the bottom of your library in a random order.
        this.addAbility(new SimpleStaticAbility(new PavelChekovFirstPostingEffect()));
    }

    private PavelChekovFirstPosting(final PavelChekovFirstPosting card) {
        super(card);
    }

    @Override
    public PavelChekovFirstPosting copy() {
        return new PavelChekovFirstPosting(this);
    }
}

class PavelChekovFirstPostingEffect extends ReplacementEffectImpl {

    PavelChekovFirstPostingEffect() {
        super(Duration.WhileOnBattlefield, Outcome.Benefit);
        staticText = "If you would draw a card, you may instead choose land or nonland and reveal cards from the top "
                + "of your library until you reveal a card of the chosen kind. Put that card into your hand and put "
                + "all other cards revealed this way on the bottom of your library in a random order";
    }

    private PavelChekovFirstPostingEffect(final PavelChekovFirstPostingEffect effect) {
        super(effect);
    }

    @Override
    public PavelChekovFirstPostingEffect copy() {
        return new PavelChekovFirstPostingEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.DRAW_CARD;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        if (!event.getPlayerId().equals(source.getControllerId())) {
            return false;
        }
        Player player = game.getPlayer(source.getControllerId());
        return player != null && player.chooseUse(Outcome.Benefit, "Choose land or nonland and reveal instead of drawing?",
                CardUtil.getSourceName(game, source), "Reveal for land or nonland", "Draw normally", source, game);
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        Player controller = game.getPlayer(event.getPlayerId());
        if (controller == null) {
            return false;
        }
        boolean land = controller.chooseUse(Outcome.Benefit, "Choose land or nonland",
                CardUtil.getSourceName(game, source), "Land", "Nonland", source, game);
        game.informPlayers(controller.getLogName() + " chooses " + (land ? "land." : "nonland."));
        Cards revealed = new CardsImpl();
        Card selected = null;
        for (Card card : controller.getLibrary().getCards(game)) {
            revealed.add(card);
            if (card.isLand(game) == land) {
                selected = card;
                break;
            }
        }
        controller.revealCards(CardUtil.getSourceName(game, source), revealed, game);
        if (selected != null) {
            controller.moveCards(selected, Zone.HAND, source, game);
            revealed.remove(selected);
        }
        controller.putCardsOnBottomOfLibrary(revealed, game, source, false);
        return true;
    }
}
