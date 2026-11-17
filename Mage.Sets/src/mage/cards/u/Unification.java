package mage.cards.u;

import mage.abilities.Ability;
import mage.abilities.common.SagaAbility;
import mage.abilities.effects.Effects;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.common.TargetCreaturePermanent;
import mage.target.common.TargetOpponent;

import java.util.UUID;

public final class Unification extends CardImpl {

    public Unification(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{3}{W}{U}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after III.)
        SagaAbility sagaAbility = new SagaAbility(this);

        // I -- The owner of up to one target creature draws two cards, then shuffles that creature into their library.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I,
                new Effects(new UnificationChapterOneEffect()), new TargetCreaturePermanent(0, 1));

        // II -- You may put a Spacecraft card from your hand or graveyard onto the battlefield.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_II, new UnificationChapterTwoEffect());

        // III -- You and target opponent each draw two cards.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_III,
                new Effects(new UnificationChapterThreeEffect()), new TargetOpponent());

        this.addAbility(sagaAbility);
    }

    private Unification(final Unification card) {
        super(card);
    }

    @Override
    public Unification copy() {
        return new Unification(this);
    }
}

class UnificationChapterOneEffect extends OneShotEffect {

    UnificationChapterOneEffect() {
        super(Outcome.Removal);
        staticText = "the owner of up to one target creature draws two cards, then shuffles that creature into their library";
    }

    private UnificationChapterOneEffect(final UnificationChapterOneEffect effect) {
        super(effect);
    }

    @Override
    public UnificationChapterOneEffect copy() {
        return new UnificationChapterOneEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature == null) {
            return false;
        }
        Player owner = game.getPlayer(creature.getOwnerId());
        if (owner == null) {
            return false;
        }
        owner.drawCards(2, source, game);
        owner.shuffleCardsToLibrary(creature, game, source);
        return true;
    }
}

class UnificationChapterTwoEffect extends OneShotEffect {

    private static final FilterCard filter = new FilterCard("Spacecraft card");

    static {
        filter.add(SubType.SPACECRAFT.getPredicate());
    }

    UnificationChapterTwoEffect() {
        super(Outcome.PutCardInPlay);
        staticText = "you may put a Spacecraft card from your hand or graveyard onto the battlefield";
    }

    private UnificationChapterTwoEffect(final UnificationChapterTwoEffect effect) {
        super(effect);
    }

    @Override
    public UnificationChapterTwoEffect copy() {
        return new UnificationChapterTwoEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        Cards cards = new CardsImpl();
        controller.getHand().getCards(filter, game).forEach(cards::add);
        controller.getGraveyard().getCards(filter, game).forEach(cards::add);
        if (cards.isEmpty() || !controller.chooseUse(Outcome.PutCardInPlay,
                "Put a Spacecraft card from your hand or graveyard onto the battlefield?", source, game)) {
            return false;
        }
        TargetCard target = new TargetCard(Zone.ALL, filter);
        target.withNotTarget(true);
        controller.choose(Outcome.PutCardInPlay, cards, target, source, game);
        Card card = game.getCard(target.getFirstTarget());
        return card != null && controller.moveCards(card, Zone.BATTLEFIELD, source, game);
    }
}

class UnificationChapterThreeEffect extends OneShotEffect {

    UnificationChapterThreeEffect() {
        super(Outcome.DrawCard);
        staticText = "you and target opponent each draw two cards";
    }

    private UnificationChapterThreeEffect(final UnificationChapterThreeEffect effect) {
        super(effect);
    }

    @Override
    public UnificationChapterThreeEffect copy() {
        return new UnificationChapterThreeEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player opponent = game.getPlayer(getTargetPointer().getFirst(game, source));
        if (controller != null) {
            controller.drawCards(2, source, game);
        }
        if (opponent != null) {
            opponent.drawCards(2, source, game);
        }
        return true;
    }
}
