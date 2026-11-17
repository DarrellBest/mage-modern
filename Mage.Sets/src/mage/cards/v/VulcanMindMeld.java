package mage.cards.v;

import mage.abilities.Ability;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.asthought.MayLookAtTargetCardEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetOpponent;
import mage.target.targetpointer.FixedTarget;
import mage.util.CardUtil;

import java.util.UUID;

public final class VulcanMindMeld extends CardImpl {

    public VulcanMindMeld(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{U}");

        // Target opponent looks at the top card of your library, then exiles it face down. You look at the top two
        // cards of their library, then exile them face down. For each card exiled this way, the player who exiled it
        // may play it for as long as it remains exiled. Mana of any type can be spent to cast spells this way.
        this.getSpellAbility().addEffect(new VulcanMindMeldEffect());
        this.getSpellAbility().addTarget(new TargetOpponent());
    }

    private VulcanMindMeld(final VulcanMindMeld card) {
        super(card);
    }

    @Override
    public VulcanMindMeld copy() {
        return new VulcanMindMeld(this);
    }
}

class VulcanMindMeldEffect extends OneShotEffect {

    VulcanMindMeldEffect() {
        super(Outcome.PlayForFree);
        staticText = "target opponent looks at the top card of your library, then exiles it face down. You look at "
                + "the top two cards of their library, then exile them face down. For each card exiled this way, the "
                + "player who exiled it may play it for as long as it remains exiled. Mana of any type can be spent "
                + "to cast spells this way";
    }

    private VulcanMindMeldEffect(final VulcanMindMeldEffect effect) {
        super(effect);
    }

    @Override
    public VulcanMindMeldEffect copy() {
        return new VulcanMindMeldEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player opponent = game.getPlayer(getTargetPointer().getFirst(game, source));
        if (controller == null || opponent == null) {
            return false;
        }
        exileFor(opponent, new CardsImpl(controller.getLibrary().getTopCards(game, 1)), game, source);
        exileFor(controller, new CardsImpl(opponent.getLibrary().getTopCards(game, 2)), game, source);
        return true;
    }

    /** {@code player} looks at and exiles {@code cards} face down, and may play them while they remain exiled. */
    private static void exileFor(Player player, Cards cards, Game game, Ability source) {
        if (cards.isEmpty()) {
            return;
        }
        player.lookAtCards(source, CardUtil.getSourceName(game, source), cards, game);
        UUID exileId = CardUtil.getExileZoneId(game, source.getSourceId(), source.getStackMomentSourceZCC());
        for (Card card : cards.getCards(game)) {
            card.setFaceDown(true, game);
            if (player.moveCardsToExile(card, source, game, false, exileId, CardUtil.getSourceName(game, source))) {
                card.setFaceDown(true, game);
                CardUtil.makeCardPlayable(game, source, card, false, Duration.Custom, true, player.getId(), null);
                ContinuousEffect look = new MayLookAtTargetCardEffect(player.getId());
                look.setTargetPointer(new FixedTarget(card.getId(), game));
                game.addEffect(look, source);
            }
        }
    }
}
