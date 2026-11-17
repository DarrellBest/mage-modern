package mage.cards.r;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.GainControlTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.filter.StaticFilters;
import mage.filter.common.FilterNonlandPermanent;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetPermanent;
import mage.target.common.TargetOpponent;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class RulesOfAcquisition extends CardImpl {

    private static final FilterNonlandPermanent opponentsPermanent = new FilterNonlandPermanent("nonland permanent an opponent controls");
    private static final FilterNonlandPermanent yourPermanent = new FilterNonlandPermanent("nonland permanent you control");

    static {
        opponentsPermanent.add(mage.constants.TargetController.OPPONENT.getControllerPredicate());
        yourPermanent.add(mage.constants.TargetController.YOU.getControllerPredicate());
    }

    public RulesOfAcquisition(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{3}{U}{U}");

        // Choose one --
        // * Follow the 3rd Rule -- Gain control of target nonland permanent an opponent controls. That opponent draws three cards.
        this.getSpellAbility().addEffect(new RulesOfAcquisitionThirdEffect());
        this.getSpellAbility().addTarget(new TargetPermanent(opponentsPermanent));
        this.getSpellAbility().getModes().getMode().withFlavorWord("Follow the 3rd Rule");

        // * Follow the 98th Rule -- Target opponent gains control of target nonland permanent you control. If they do, you draw three cards.
        Mode mode = new Mode(new RulesOfAcquisitionNinetyEighthEffect());
        mode.addTarget(new TargetOpponent());
        mode.addTarget(new TargetPermanent(yourPermanent));
        this.getSpellAbility().addMode(mode.withFlavorWord("Follow the 98th Rule"));
    }

    private RulesOfAcquisition(final RulesOfAcquisition card) {
        super(card);
    }

    @Override
    public RulesOfAcquisition copy() {
        return new RulesOfAcquisition(this);
    }
}

class RulesOfAcquisitionThirdEffect extends OneShotEffect {

    RulesOfAcquisitionThirdEffect() {
        super(Outcome.GainControl);
        staticText = "gain control of target nonland permanent an opponent controls. That opponent draws three cards";
    }

    private RulesOfAcquisitionThirdEffect(final RulesOfAcquisitionThirdEffect effect) {
        super(effect);
    }

    @Override
    public RulesOfAcquisitionThirdEffect copy() {
        return new RulesOfAcquisitionThirdEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent permanent = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (permanent == null) {
            return false;
        }
        Player opponent = game.getPlayer(permanent.getControllerId());
        ContinuousEffect control = new GainControlTargetEffect(Duration.Custom, true);
        control.setTargetPointer(new FixedTarget(permanent, game));
        game.addEffect(control, source);
        game.processAction();
        if (opponent != null) {
            opponent.drawCards(3, source, game);
        }
        return true;
    }
}

class RulesOfAcquisitionNinetyEighthEffect extends OneShotEffect {

    RulesOfAcquisitionNinetyEighthEffect() {
        super(Outcome.DrawCard);
        staticText = "target opponent gains control of target nonland permanent you control. If they do, you draw three cards";
    }

    private RulesOfAcquisitionNinetyEighthEffect(final RulesOfAcquisitionNinetyEighthEffect effect) {
        super(effect);
    }

    @Override
    public RulesOfAcquisitionNinetyEighthEffect copy() {
        return new RulesOfAcquisitionNinetyEighthEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player opponent = game.getPlayer(source.getTargets().get(0).getFirstTarget());
        Permanent permanent = game.getPermanent(source.getTargets().get(1).getFirstTarget());
        if (controller == null || opponent == null || permanent == null) {
            return false;
        }
        ContinuousEffect control = new GainControlTargetEffect(Duration.Custom, true, opponent.getId());
        control.setTargetPointer(new FixedTarget(permanent, game));
        game.addEffect(control, source);
        game.processAction();
        Permanent after = game.getPermanent(permanent.getId());
        if (after != null && after.isControlledBy(opponent.getId())) {
            controller.drawCards(3, source, game);
        }
        return true;
    }
}
