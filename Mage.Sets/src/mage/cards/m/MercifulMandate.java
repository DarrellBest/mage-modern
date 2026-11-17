package mage.cards.m;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.UntapAllControllerEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.TurnPhase;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.players.Player;

import java.util.UUID;

public final class MercifulMandate extends CardImpl {

    public MercifulMandate(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.INSTANT}, "{1}{W}");

        // Untap all creatures you control.
        this.getSpellAbility().addEffect(new UntapAllControllerEffect(
                StaticFilters.FILTER_PERMANENT_CREATURES, "untap all creatures you control"
        ));
        // If it's a combat phase, you may end the combat phase.
        this.getSpellAbility().addEffect(new MercifulMandateEffect());
    }

    private MercifulMandate(final MercifulMandate card) {
        super(card);
    }

    @Override
    public MercifulMandate copy() {
        return new MercifulMandate(this);
    }
}

class MercifulMandateEffect extends OneShotEffect {

    MercifulMandateEffect() {
        super(Outcome.Benefit);
        staticText = "If it's a combat phase, you may end the combat phase. <i>(Remove all attackers and blockers "
                + "from combat. Exile all spells and abilities from the stack, including this spell.)</i>";
    }

    private MercifulMandateEffect(final MercifulMandateEffect effect) {
        super(effect);
    }

    @Override
    public MercifulMandateEffect copy() {
        return new MercifulMandateEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null || game.getTurnPhaseType() != TurnPhase.COMBAT
                || !controller.chooseUse(Outcome.Benefit, "End the combat phase?", source, game)) {
            return true;
        }
        // same "end the combat phase" handling as Mandate of Peace (this package)
        return new MandateOfPeaceEndCombatEffect().apply(game, source);
    }
}
