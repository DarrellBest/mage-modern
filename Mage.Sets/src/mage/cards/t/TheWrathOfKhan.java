package mage.cards.t;

import mage.abilities.Ability;
import mage.abilities.DelayedTriggeredAbility;
import mage.abilities.common.SagaAbility;
import mage.abilities.effects.Effects;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.DamageTargetEffect;
import mage.abilities.effects.common.UntapTargetEffect;
import mage.abilities.effects.common.continuous.GainAbilityTargetEffect;
import mage.abilities.effects.common.continuous.GainControlTargetEffect;
import mage.abilities.keyword.HasteAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.filter.common.FilterNonlandPermanent;
import mage.filter.predicate.permanent.TokenPredicate;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.common.TargetCardInHand;
import mage.target.common.TargetOpponentsCreaturePermanent;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class TheWrathOfKhan extends CardImpl {

    private static final FilterNonlandPermanent nontoken = new FilterNonlandPermanent("nontoken permanent");

    static {
        nontoken.add(TokenPredicate.FALSE);
    }

    public TheWrathOfKhan(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{2}{R}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after III.)
        SagaAbility sagaAbility = new SagaAbility(this);

        // I -- This Saga deals 4 damage to target creature an opponent controls. At the beginning of the next combat
        // this turn, gain control of that creature until end of turn. Untap it. It gains haste until end of turn.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I,
                new Effects(new TheWrathOfKhanChapterOneEffect()), new TargetOpponentsCreaturePermanent());

        // II -- You may exile a card from your hand. If you do, draw a card.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_II, new TheWrathOfKhanChapterTwoEffect());

        // III -- Each opponent sacrifices a nontoken permanent of their choice.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_III,
                new mage.abilities.effects.common.SacrificeOpponentsEffect(nontoken));

        this.addAbility(sagaAbility);
    }

    private TheWrathOfKhan(final TheWrathOfKhan card) {
        super(card);
    }

    @Override
    public TheWrathOfKhan copy() {
        return new TheWrathOfKhan(this);
    }
}

class TheWrathOfKhanChapterOneEffect extends OneShotEffect {

    TheWrathOfKhanChapterOneEffect() {
        super(Outcome.Damage);
        staticText = "this Saga deals 4 damage to target creature an opponent controls. At the beginning of the next "
                + "combat this turn, gain control of that creature until end of turn. Untap it. It gains haste until end of turn";
    }

    private TheWrathOfKhanChapterOneEffect(final TheWrathOfKhanChapterOneEffect effect) {
        super(effect);
    }

    @Override
    public TheWrathOfKhanChapterOneEffect copy() {
        return new TheWrathOfKhanChapterOneEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature == null) {
            return false;
        }
        new DamageTargetEffect(4).setTargetPointer(new FixedTarget(creature, game)).apply(game, source);
        // the delayed part applies to the same object, if it survived
        game.addDelayedTriggeredAbility(new TheWrathOfKhanDelayedAbility(new FixedTarget(creature, game)), source);
        return true;
    }
}

class TheWrathOfKhanDelayedAbility extends DelayedTriggeredAbility {

    TheWrathOfKhanDelayedAbility(FixedTarget target) {
        super(new GainControlTargetEffect(Duration.EndOfTurn), Duration.EndOfTurn, true, false);
        this.addEffect(new UntapTargetEffect().setText("untap it"));
        this.addEffect(new GainAbilityTargetEffect(HasteAbility.getInstance(), Duration.EndOfTurn)
                .setText("it gains haste until end of turn"));
        this.getEffects().setTargetPointer(target);
    }

    private TheWrathOfKhanDelayedAbility(final TheWrathOfKhanDelayedAbility ability) {
        super(ability);
    }

    @Override
    public TheWrathOfKhanDelayedAbility copy() {
        return new TheWrathOfKhanDelayedAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.COMBAT_PHASE_PRE;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        return true;
    }

    @Override
    public String getRule() {
        return "At the beginning of the next combat this turn, gain control of that creature until end of turn. "
                + "Untap it. It gains haste until end of turn.";
    }
}

class TheWrathOfKhanChapterTwoEffect extends OneShotEffect {

    TheWrathOfKhanChapterTwoEffect() {
        super(Outcome.DrawCard);
        staticText = "you may exile a card from your hand. If you do, draw a card";
    }

    private TheWrathOfKhanChapterTwoEffect(final TheWrathOfKhanChapterTwoEffect effect) {
        super(effect);
    }

    @Override
    public TheWrathOfKhanChapterTwoEffect copy() {
        return new TheWrathOfKhanChapterTwoEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null || controller.getHand().isEmpty()
                || !controller.chooseUse(Outcome.DrawCard, "Exile a card from your hand to draw a card?", source, game)) {
            return false;
        }
        TargetCardInHand target = new TargetCardInHand(StaticFilters.FILTER_CARD);
        controller.choose(Outcome.Exile, target, source, game);
        Card card = game.getCard(target.getFirstTarget());
        if (card != null && controller.moveCards(card, Zone.EXILED, source, game)) {
            controller.drawCards(1, source, game);
        }
        return true;
    }
}
