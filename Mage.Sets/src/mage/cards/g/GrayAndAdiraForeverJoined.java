package mage.cards.g;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.condition.Condition;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenCopyTargetEffect;
import mage.abilities.triggers.BeginningOfCombatTriggeredAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.common.TargetControlledCreaturePermanent;
import mage.target.targetpointer.FixedTarget;
import mage.watchers.common.SpellsCastWatcher;

import java.util.UUID;

public final class GrayAndAdiraForeverJoined extends CardImpl {

    public GrayAndAdiraForeverJoined(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U/R}{U/R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.TRILL);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(3);

        // At the beginning of combat on your turn, if you've cast a noncreature spell this turn, create a token that's
        // a copy of target creature you control, except it isn't legendary. It gains haste until end of turn.
        // Sacrifice it at the beginning of the next end step.
        Ability ability = new BeginningOfCombatTriggeredAbility(new GrayAndAdiraForeverJoinedEffect())
                .withInterveningIf(GrayAndAdiraForeverJoinedCondition.instance);
        ability.addTarget(new TargetControlledCreaturePermanent());
        ability.addWatcher(new SpellsCastWatcher());
        this.addAbility(ability);
    }

    private GrayAndAdiraForeverJoined(final GrayAndAdiraForeverJoined card) {
        super(card);
    }

    @Override
    public GrayAndAdiraForeverJoined copy() {
        return new GrayAndAdiraForeverJoined(this);
    }
}

enum GrayAndAdiraForeverJoinedCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        SpellsCastWatcher watcher = game.getState().getWatcher(SpellsCastWatcher.class);
        return watcher != null && watcher.getSpellsCastThisTurn(source.getControllerId()).stream()
                .anyMatch(spell -> !spell.isCreature(game));
    }

    @Override
    public String toString() {
        return "you've cast a noncreature spell this turn";
    }
}

class GrayAndAdiraForeverJoinedEffect extends OneShotEffect {

    GrayAndAdiraForeverJoinedEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "create a token that's a copy of target creature you control, except it isn't legendary. "
                + "It gains haste until end of turn. Sacrifice it at the beginning of the next end step";
    }

    private GrayAndAdiraForeverJoinedEffect(final GrayAndAdiraForeverJoinedEffect effect) {
        super(effect);
    }

    @Override
    public GrayAndAdiraForeverJoinedEffect copy() {
        return new GrayAndAdiraForeverJoinedEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature == null) {
            return false;
        }
        CreateTokenCopyTargetEffect effect = new CreateTokenCopyTargetEffect(null, null, true);
        effect.setIsntLegendary(true);
        effect.setTargetPointer(new FixedTarget(creature, game));
        effect.apply(game, source);
        effect.sacrificeTokensCreatedAtNextEndStep(game, source);
        return true;
    }
}
