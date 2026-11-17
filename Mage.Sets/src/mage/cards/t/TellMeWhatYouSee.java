package mage.cards.t;

import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.ReturnToHandSourceEffect;
import mage.abilities.effects.keyword.SurveilEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetOpponent;

import java.util.UUID;

public final class TellMeWhatYouSee extends CardImpl {

    public TellMeWhatYouSee(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{1}{B}");

        // When this enchantment enters, target opponent may have you draw a card. If they don't, they lose 4 life and you surveil 1.
        Ability ability = new EntersBattlefieldTriggeredAbility(new TellMeWhatYouSeeEffect());
        ability.addTarget(new TargetOpponent());
        this.addAbility(ability);

        // Whenever you face a dilemma, you may return this enchantment to its owner's hand.
        this.addAbility(new FaceDilemmaTriggeredAbility(
                new ReturnToHandSourceEffect(true).setText("return this enchantment to its owner's hand"), true
        ));
    }

    private TellMeWhatYouSee(final TellMeWhatYouSee card) {
        super(card);
    }

    @Override
    public TellMeWhatYouSee copy() {
        return new TellMeWhatYouSee(this);
    }
}

class TellMeWhatYouSeeEffect extends OneShotEffect {

    TellMeWhatYouSeeEffect() {
        super(Outcome.Benefit);
        staticText = "target opponent may have you draw a card. If they don't, they lose 4 life and you surveil 1";
    }

    private TellMeWhatYouSeeEffect(final TellMeWhatYouSeeEffect effect) {
        super(effect);
    }

    @Override
    public TellMeWhatYouSeeEffect copy() {
        return new TellMeWhatYouSeeEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player opponent = game.getPlayer(getTargetPointer().getFirst(game, source));
        if (controller == null || opponent == null) {
            return false;
        }
        if (opponent.chooseUse(Outcome.Detriment, "Have " + controller.getName() + " draw a card? "
                + "If you don't, you lose 4 life and they surveil 1.", source, game)) {
            controller.drawCards(1, source, game);
        } else {
            opponent.loseLife(4, game, source, false);
            new SurveilEffect(1).apply(game, source);
        }
        return true;
    }
}
