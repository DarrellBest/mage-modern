package mage.cards.r;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.DrawNthCardTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenCopyTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.TargetPermanent;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class RegBarclayFidgetyFantasist extends CardImpl {

    public RegBarclayFidgetyFantasist(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(1);
        this.toughness = new MageInt(5);

        // Seek out -- Whenever you draw your second card each turn, create a token that's a copy of another target
        // creature you control, except it isn't legendary, it's an Illusion artifact in addition to its other types,
        // and it has haste. Exile that token at the beginning of the next end step.
        Ability ability = new DrawNthCardTriggeredAbility(new RegBarclayFidgetyFantasistEffect());
        ability.addTarget(new TargetPermanent(StaticFilters.FILTER_ANOTHER_CREATURE_YOU_CONTROL));
        this.addAbility(ability.withFlavorWord("Seek out"));
    }

    private RegBarclayFidgetyFantasist(final RegBarclayFidgetyFantasist card) {
        super(card);
    }

    @Override
    public RegBarclayFidgetyFantasist copy() {
        return new RegBarclayFidgetyFantasist(this);
    }
}

class RegBarclayFidgetyFantasistEffect extends OneShotEffect {

    RegBarclayFidgetyFantasistEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "create a token that's a copy of another target creature you control, except it isn't legendary, "
                + "it's an Illusion artifact in addition to its other types, and it has haste. "
                + "Exile that token at the beginning of the next end step";
    }

    private RegBarclayFidgetyFantasistEffect(final RegBarclayFidgetyFantasistEffect effect) {
        super(effect);
    }

    @Override
    public RegBarclayFidgetyFantasistEffect copy() {
        return new RegBarclayFidgetyFantasistEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature == null) {
            return false;
        }
        CreateTokenCopyTargetEffect effect = new CreateTokenCopyTargetEffect(null, CardType.ARTIFACT, true);
        effect.setIsntLegendary(true);
        effect.withAdditionalSubType(SubType.ILLUSION);
        effect.setTargetPointer(new FixedTarget(creature, game));
        effect.apply(game, source);
        effect.exileTokensCreatedAtNextEndStep(game, source);
        return true;
    }
}
