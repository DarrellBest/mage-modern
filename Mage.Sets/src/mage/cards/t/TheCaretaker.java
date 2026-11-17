package mage.cards.t;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.condition.Condition;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.VigilanceAbility;
import mage.abilities.keyword.WardAbility;
import mage.abilities.triggers.BeginningOfEndStepTriggeredAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.TargetController;
import mage.constants.Zone;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetOpponent;
import mage.util.CardUtil;
import mage.watchers.common.FacedDilemmaWatcher;

import java.util.UUID;

public final class TheCaretaker extends CardImpl {

    public TheCaretaker(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{5}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.ELEMENTAL);
        this.power = new MageInt(5);
        this.toughness = new MageInt(6);

        // Vigilance, ward {2}
        this.addAbility(VigilanceAbility.getInstance());
        this.addAbility(new WardAbility(new GenericManaCost(2)));

        // At the beginning of each end step, if you faced a dilemma this turn, target opponent exiles cards from the
        // top of their library until they exile a nonland card. You may cast that card without paying its mana cost.
        Ability ability = new BeginningOfEndStepTriggeredAbility(
                TargetController.ANY, new TheCaretakerEffect(), false
        ).withInterveningIf(TheCaretakerCondition.instance);
        ability.addTarget(new TargetOpponent());
        ability.addWatcher(new FacedDilemmaWatcher());
        this.addAbility(ability);
    }

    private TheCaretaker(final TheCaretaker card) {
        super(card);
    }

    @Override
    public TheCaretaker copy() {
        return new TheCaretaker(this);
    }
}

enum TheCaretakerCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        return FacedDilemmaWatcher.getCount(source.getControllerId(), game) > 0;
    }

    @Override
    public String toString() {
        return "you faced a dilemma this turn";
    }
}

class TheCaretakerEffect extends OneShotEffect {

    TheCaretakerEffect() {
        super(Outcome.PlayForFree);
        staticText = "target opponent exiles cards from the top of their library until they exile a nonland card. "
                + "You may cast that card without paying its mana cost";
    }

    private TheCaretakerEffect(final TheCaretakerEffect effect) {
        super(effect);
    }

    @Override
    public TheCaretakerEffect copy() {
        return new TheCaretakerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player opponent = game.getPlayer(getTargetPointer().getFirst(game, source));
        if (controller == null || opponent == null) {
            return false;
        }
        for (Card card : opponent.getLibrary().getCards(game)) {
            opponent.moveCards(card, Zone.EXILED, source, game);
            if (!card.isLand(game)) {
                CardUtil.castSpellWithAttributesForFree(controller, source, game, card);
                break;
            }
        }
        return true;
    }
}
