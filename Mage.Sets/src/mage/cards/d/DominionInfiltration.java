package mage.cards.d;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenCopyTargetEffect;
import mage.abilities.keyword.ChangelingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.common.TargetOpponentsCreaturePermanent;
import mage.target.targetadjustment.XTargetsCountAdjuster;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class DominionInfiltration extends CardImpl {

    public DominionInfiltration(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{X}{R}{R}{R}");

        // Choose X target creatures your opponents control. For each of them, create a token that's a copy of that
        // creature, except it has changeling. Those tokens gain haste. Exile those tokens at the beginning of the next end step.
        this.getSpellAbility().addEffect(new DominionInfiltrationEffect());
        this.getSpellAbility().addTarget(new TargetOpponentsCreaturePermanent());
        this.getSpellAbility().setTargetAdjuster(new XTargetsCountAdjuster());
    }

    private DominionInfiltration(final DominionInfiltration card) {
        super(card);
    }

    @Override
    public DominionInfiltration copy() {
        return new DominionInfiltration(this);
    }
}

class DominionInfiltrationEffect extends OneShotEffect {

    DominionInfiltrationEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "choose X target creatures your opponents control. For each of them, create a token that's a copy "
                + "of that creature, except it has changeling. Those tokens gain haste. "
                + "Exile those tokens at the beginning of the next end step";
    }

    private DominionInfiltrationEffect(final DominionInfiltrationEffect effect) {
        super(effect);
    }

    @Override
    public DominionInfiltrationEffect copy() {
        return new DominionInfiltrationEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        for (UUID id : getTargetPointer().getTargets(game, source)) {
            Permanent creature = game.getPermanent(id);
            if (creature == null) {
                continue;
            }
            CreateTokenCopyTargetEffect effect = new CreateTokenCopyTargetEffect(null, null, true);
            effect.addAdditionalAbilities(new ChangelingAbility());
            effect.setTargetPointer(new FixedTarget(creature, game));
            effect.apply(game, source);
            effect.exileTokensCreatedAtNextEndStep(game, source);
        }
        return true;
    }
}
