package mage.cards.t;

import mage.abilities.Ability;
import mage.abilities.DelayedTriggeredAbility;
import mage.abilities.common.SagaAbility;
import mage.abilities.dynamicvalue.common.GreatestAmongPermanentsValue;
import mage.abilities.effects.Effects;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.GainLifeEffect;
import mage.abilities.effects.common.ReturnFromGraveyardToBattlefieldTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.filter.common.FilterCreatureCard;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.game.events.DamagedEvent;
import mage.game.events.GameEvent;
import mage.game.permanent.token.TreasureToken;
import mage.target.common.TargetCardInYourGraveyard;
import mage.target.common.TargetCreaturePermanent;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class TheInnerLight extends CardImpl {

    private static final FilterCreatureCard filter = new FilterCreatureCard("green or white creature card from your graveyard");

    static {
        filter.add(Predicates.or(
                new mage.filter.predicate.mageobject.ColorPredicate(mage.ObjectColor.GREEN),
                new mage.filter.predicate.mageobject.ColorPredicate(mage.ObjectColor.WHITE)
        ));
    }

    public TheInnerLight(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{3}{G}{W}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after III.)
        SagaAbility sagaAbility = new SagaAbility(this);

        // I -- Return target green or white creature card from your graveyard to the battlefield.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I,
                new Effects(new ReturnFromGraveyardToBattlefieldTargetEffect()), new TargetCardInYourGraveyard(filter));

        // II -- Choose target creature. Whenever it deals combat damage to a player this turn, you draw that many cards.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_II,
                new Effects(new TheInnerLightChapterTwoEffect()), new TargetCreaturePermanent());

        // III -- You gain life equal to the greatest power among creatures you control. Create a Treasure token.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_III,
                new GainLifeEffect(GreatestAmongPermanentsValue.POWER_CONTROLLED_CREATURES)
                        .setText("you gain life equal to the greatest power among creatures you control"),
                new CreateTokenEffect(new TreasureToken()));

        this.addAbility(sagaAbility);
    }

    private TheInnerLight(final TheInnerLight card) {
        super(card);
    }

    @Override
    public TheInnerLight copy() {
        return new TheInnerLight(this);
    }
}

class TheInnerLightChapterTwoEffect extends OneShotEffect {

    TheInnerLightChapterTwoEffect() {
        super(Outcome.DrawCard);
        staticText = "choose target creature. Whenever it deals combat damage to a player this turn, you draw that many cards";
    }

    private TheInnerLightChapterTwoEffect(final TheInnerLightChapterTwoEffect effect) {
        super(effect);
    }

    @Override
    public TheInnerLightChapterTwoEffect copy() {
        return new TheInnerLightChapterTwoEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        mage.game.permanent.Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature == null) {
            return false;
        }
        game.addDelayedTriggeredAbility(new TheInnerLightDelayedAbility(new FixedTarget(creature, game)), source);
        return true;
    }
}

class TheInnerLightDelayedAbility extends DelayedTriggeredAbility {

    private final FixedTarget creature;

    TheInnerLightDelayedAbility(FixedTarget creature) {
        super(new DrawCardSourceControllerEffect(0), Duration.EndOfTurn, false, false);
        this.creature = creature;
    }

    private TheInnerLightDelayedAbility(final TheInnerLightDelayedAbility ability) {
        super(ability);
        this.creature = ability.creature;
    }

    @Override
    public TheInnerLightDelayedAbility copy() {
        return new TheInnerLightDelayedAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.DAMAGED_PLAYER;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        if (!((DamagedEvent) event).isCombatDamage()
                || !event.getSourceId().equals(creature.getTarget())
                || game.getState().getZoneChangeCounter(event.getSourceId()) != creature.getZoneChangeCounter()) {
            return false;
        }
        this.getEffects().clear();
        this.addEffect(new DrawCardSourceControllerEffect(event.getAmount()));
        return true;
    }

    @Override
    public String getRule() {
        return "Whenever that creature deals combat damage to a player this turn, you draw that many cards.";
    }
}
