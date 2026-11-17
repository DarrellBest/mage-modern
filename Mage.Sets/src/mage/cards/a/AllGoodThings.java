package mage.cards.a;

import mage.abilities.Ability;
import mage.abilities.common.SagaAbility;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.filter.StaticFilters;
import mage.game.ExileZone;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetCardInHand;
import mage.util.CardUtil;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AllGoodThings extends CardImpl {

    public AllGoodThings(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{3}{G}{U}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after IV.)
        SagaAbility sagaAbility = new SagaAbility(this, SagaChapter.CHAPTER_IV);

        // I, II, III -- Draw two cards, then exile a card from your hand.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I, SagaChapter.CHAPTER_III, new AllGoodThingsDrawEffect());

        // IV -- If all cards exiled with this Saga share a card type, you gain 10 life.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_IV, new AllGoodThingsLifeEffect());

        this.addAbility(sagaAbility);
    }

    private AllGoodThings(final AllGoodThings card) {
        super(card);
    }

    @Override
    public AllGoodThings copy() {
        return new AllGoodThings(this);
    }
}

class AllGoodThingsDrawEffect extends OneShotEffect {

    AllGoodThingsDrawEffect() {
        super(Outcome.DrawCard);
        staticText = "draw two cards, then exile a card from your hand";
    }

    private AllGoodThingsDrawEffect(final AllGoodThingsDrawEffect effect) {
        super(effect);
    }

    @Override
    public AllGoodThingsDrawEffect copy() {
        return new AllGoodThingsDrawEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        controller.drawCards(2, source, game);
        if (controller.getHand().isEmpty()) {
            return true;
        }
        TargetCardInHand target = new TargetCardInHand(StaticFilters.FILTER_CARD);
        target.withChooseHint("to exile with " + CardUtil.getSourceName(game, source));
        controller.choose(Outcome.Exile, target, source, game);
        Card card = game.getCard(target.getFirstTarget());
        if (card != null) {
            controller.moveCardsToExile(card, source, game, true,
                    CardUtil.getExileZoneId(game, source), CardUtil.getSourceName(game, source));
        }
        return true;
    }
}

class AllGoodThingsLifeEffect extends OneShotEffect {

    AllGoodThingsLifeEffect() {
        super(Outcome.GainLife);
        staticText = "if all cards exiled with this Saga share a card type, you gain 10 life";
    }

    private AllGoodThingsLifeEffect(final AllGoodThingsLifeEffect effect) {
        super(effect);
    }

    @Override
    public AllGoodThingsLifeEffect copy() {
        return new AllGoodThingsLifeEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        ExileZone exile = game.getExile().getExileZone(CardUtil.getExileZoneId(game, source));
        if (controller == null || exile == null || exile.isEmpty()) {
            return false;
        }
        Set<CardType> shared = EnumSet.allOf(CardType.class);
        for (Card card : exile.getCards(game)) {
            List<CardType> types = card.getCardType(game);
            shared.retainAll(types);
        }
        if (!shared.isEmpty()) {
            controller.gainLife(10, game, source);
        }
        return true;
    }
}
