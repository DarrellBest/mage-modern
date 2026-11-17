package mage.cards.q;

import mage.MageInt;
import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbilityImpl;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.condition.Condition;
import mage.abilities.costs.common.ExileFromHandCost;
import mage.abilities.effects.ContinuousEffectImpl;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Layer;
import mage.constants.Outcome;
import mage.constants.SubLayer;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetCardInHand;
import mage.util.CardUtil;

import java.util.UUID;

public final class QStackingTheDeck extends CardImpl {

    public QStackingTheDeck(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{R}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.Q);
        this.power = new MageInt(4);
        this.toughness = new MageInt(4);

        // When Q enters, shuffle your library, then exile cards from the top of your library until you exile a nonland
        // card. That nonland card gains "Exile a card from your hand: Create a copy of this card. You may cast the
        // copy. Activate only during your turn and only if this card is exiled."
        this.addAbility(new EntersBattlefieldTriggeredAbility(new QStackingTheDeckEffect()));
    }

    private QStackingTheDeck(final QStackingTheDeck card) {
        super(card);
    }

    @Override
    public QStackingTheDeck copy() {
        return new QStackingTheDeck(this);
    }
}

class QStackingTheDeckEffect extends OneShotEffect {

    QStackingTheDeckEffect() {
        super(Outcome.Benefit);
        staticText = "shuffle your library, then exile cards from the top of your library until you exile a nonland "
                + "card. That nonland card gains \"Exile a card from your hand: Create a copy of this card. You may cast "
                + "the copy. Activate only during your turn and only if this card is exiled.\"";
    }

    private QStackingTheDeckEffect(final QStackingTheDeckEffect effect) {
        super(effect);
    }

    @Override
    public QStackingTheDeckEffect copy() {
        return new QStackingTheDeckEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        controller.shuffleLibrary(source, game);
        for (Card card : controller.getLibrary().getCards(game)) {
            controller.moveCards(card, Zone.EXILED, source, game);
            if (!card.isLand(game)) {
                game.addEffect(new QStackingTheDeckGainEffect(new MageObjectReference(card, game)), source);
                break;
            }
        }
        return true;
    }
}

/** Keeps the granted ability on that exiled card for as long as it's the same object. */
class QStackingTheDeckGainEffect extends ContinuousEffectImpl {

    private final MageObjectReference mor;

    QStackingTheDeckGainEffect(MageObjectReference mor) {
        super(Duration.Custom, Layer.AbilityAddingRemovingEffects_6, SubLayer.NA, Outcome.AddAbility);
        this.mor = mor;
    }

    private QStackingTheDeckGainEffect(final QStackingTheDeckGainEffect effect) {
        super(effect);
        this.mor = effect.mor;
    }

    @Override
    public QStackingTheDeckGainEffect copy() {
        return new QStackingTheDeckGainEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Card card = mor.getCard(game);
        if (card == null) {
            discard(); // it left exile (or changed zones): a new object
            return false;
        }
        Ability ability = new QStackingTheDeckCopyAbility();
        ability.setSourceId(card.getId());
        ability.setControllerId(card.getOwnerId());
        game.getState().addOtherAbility(card, ability);
        return true;
    }
}

class QStackingTheDeckCopyAbility extends ActivatedAbilityImpl {

    QStackingTheDeckCopyAbility() {
        super(Zone.EXILED, new QStackingTheDeckCopyEffect(), new ExileFromHandCost(new TargetCardInHand()));
        this.condition = QStackingTheDeckCondition.instance;
    }

    private QStackingTheDeckCopyAbility(final QStackingTheDeckCopyAbility ability) {
        super(ability);
    }

    @Override
    public QStackingTheDeckCopyAbility copy() {
        return new QStackingTheDeckCopyAbility(this);
    }

    @Override
    public String getRule() {
        return "Exile a card from your hand: Create a copy of this card. You may cast the copy. "
                + "Activate only during your turn and only if this card is exiled.";
    }
}

enum QStackingTheDeckCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        return game.isActivePlayer(source.getControllerId()) && game.getState().getZone(source.getSourceId()) == Zone.EXILED;
    }
}

class QStackingTheDeckCopyEffect extends OneShotEffect {

    QStackingTheDeckCopyEffect() {
        super(Outcome.PlayForFree);
        staticText = "create a copy of this card. You may cast the copy";
    }

    private QStackingTheDeckCopyEffect(final QStackingTheDeckCopyEffect effect) {
        super(effect);
    }

    @Override
    public QStackingTheDeckCopyEffect copy() {
        return new QStackingTheDeckCopyEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Card card = game.getCard(source.getSourceId());
        if (controller == null || card == null) {
            return false;
        }
        Card copy = game.copyCard(card, source, controller.getId());
        if (controller.chooseUse(Outcome.PlayForFree, "Cast the copy of " + card.getName() + "?", source, game)) {
            // you pay its costs: "you may cast the copy", not "without paying"
            CardUtil.castSingle(controller, source, game, copy);
        }
        return true;
    }
}
