package mage.cards.d;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.DelayedTriggeredAbility;
import mage.abilities.Mode;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.delayed.AtTheBeginOfNextEndStepDelayedTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.ReturnFromGraveyardToHandTargetEffect;
import mage.abilities.keyword.FlashAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.filter.FilterCard;
import mage.filter.StaticFilters;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetPermanent;
import mage.target.common.TargetCardInYourGraveyard;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class DrLeonardBonesMcCoy extends CardImpl {

    private static final FilterCard nonDoctor = new FilterCard("non-Doctor permanent card from your graveyard");

    static {
        nonDoctor.add(Predicates.or(
                CardType.ARTIFACT.getPredicate(), CardType.BATTLE.getPredicate(), CardType.CREATURE.getPredicate(),
                CardType.ENCHANTMENT.getPredicate(), CardType.LAND.getPredicate(), CardType.PLANESWALKER.getPredicate()));
        nonDoctor.add(Predicates.not(SubType.DOCTOR.getPredicate()));
    }

    public DrLeonardBonesMcCoy(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{G}{G}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.DOCTOR);
        this.power = new MageInt(3);
        this.toughness = new MageInt(2);

        // Flash
        this.addAbility(FlashAbility.getInstance());

        // When Dr. McCoy enters, choose one --
        // * Return target non-Doctor permanent card from your graveyard to your hand.
        Ability ability = new EntersBattlefieldTriggeredAbility(new ReturnFromGraveyardToHandTargetEffect());
        ability.addTarget(new TargetCardInYourGraveyard(nonDoctor));

        // * Choose up to one other target creature. When it dies this turn, return that card to the battlefield under
        //   its owner's control with a +1/+1 counter on it at the beginning of the next end step.
        Mode mode = new Mode(new DrLeonardBonesMcCoyEffect());
        mode.addTarget(new TargetPermanent(0, 1, StaticFilters.FILTER_ANOTHER_TARGET_CREATURE));
        ability.addMode(mode);
        this.addAbility(ability);
    }

    private DrLeonardBonesMcCoy(final DrLeonardBonesMcCoy card) {
        super(card);
    }

    @Override
    public DrLeonardBonesMcCoy copy() {
        return new DrLeonardBonesMcCoy(this);
    }
}

class DrLeonardBonesMcCoyEffect extends OneShotEffect {

    DrLeonardBonesMcCoyEffect() {
        super(Outcome.Benefit);
        staticText = "choose up to one other target creature. When it dies this turn, return that card to the "
                + "battlefield under its owner's control with a +1/+1 counter on it at the beginning of the next end step";
    }

    private DrLeonardBonesMcCoyEffect(final DrLeonardBonesMcCoyEffect effect) {
        super(effect);
    }

    @Override
    public DrLeonardBonesMcCoyEffect copy() {
        return new DrLeonardBonesMcCoyEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature == null) {
            return false;
        }
        game.addDelayedTriggeredAbility(new DrLeonardBonesMcCoyDiesAbility(new FixedTarget(creature, game)), source);
        return true;
    }
}

class DrLeonardBonesMcCoyDiesAbility extends DelayedTriggeredAbility {

    private final FixedTarget creature;

    DrLeonardBonesMcCoyDiesAbility(FixedTarget creature) {
        super(new DrLeonardBonesMcCoyReturnLaterEffect(), Duration.EndOfTurn, true, false);
        this.creature = creature;
    }

    private DrLeonardBonesMcCoyDiesAbility(final DrLeonardBonesMcCoyDiesAbility ability) {
        super(ability);
        this.creature = ability.creature;
    }

    @Override
    public DrLeonardBonesMcCoyDiesAbility copy() {
        return new DrLeonardBonesMcCoyDiesAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ZONE_CHANGE;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        ZoneChangeEvent zce = (ZoneChangeEvent) event;
        if (!zce.isDiesEvent() || !zce.getTargetId().equals(creature.getTarget())
                || zce.getTarget() == null || zce.getTarget().getZoneChangeCounter(game) != creature.getZoneChangeCounter()) {
            return false;
        }
        // the card in the graveyard
        getEffects().setTargetPointer(new FixedTarget(zce.getTargetId(), game.getState().getZoneChangeCounter(zce.getTargetId())));
        return true;
    }

    @Override
    public String getRule() {
        return "When that creature dies this turn, return that card to the battlefield under its owner's control "
                + "with a +1/+1 counter on it at the beginning of the next end step.";
    }
}

class DrLeonardBonesMcCoyReturnLaterEffect extends OneShotEffect {

    DrLeonardBonesMcCoyReturnLaterEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "return that card to the battlefield under its owner's control with a +1/+1 counter on it "
                + "at the beginning of the next end step";
    }

    private DrLeonardBonesMcCoyReturnLaterEffect(final DrLeonardBonesMcCoyReturnLaterEffect effect) {
        super(effect);
    }

    @Override
    public DrLeonardBonesMcCoyReturnLaterEffect copy() {
        return new DrLeonardBonesMcCoyReturnLaterEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        OneShotEffect returnEffect = new DrLeonardBonesMcCoyReturnEffect();
        returnEffect.setTargetPointer(getTargetPointer().copy());
        game.addDelayedTriggeredAbility(new AtTheBeginOfNextEndStepDelayedTriggeredAbility(returnEffect), source);
        return true;
    }
}

class DrLeonardBonesMcCoyReturnEffect extends OneShotEffect {

    DrLeonardBonesMcCoyReturnEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "return that card to the battlefield under its owner's control with a +1/+1 counter on it";
    }

    private DrLeonardBonesMcCoyReturnEffect(final DrLeonardBonesMcCoyReturnEffect effect) {
        super(effect);
    }

    @Override
    public DrLeonardBonesMcCoyReturnEffect copy() {
        return new DrLeonardBonesMcCoyReturnEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Card card = game.getCard(getTargetPointer().getFirst(game, source));
        if (card == null || game.getState().getZone(card.getId()) != Zone.GRAVEYARD) {
            return false;
        }
        Player owner = game.getPlayer(card.getOwnerId());
        if (owner == null) {
            return false;
        }
        Counters counters = new Counters();
        counters.addCounter(CounterType.P1P1.createInstance());
        game.setEnterWithCounters(card.getId(), counters);
        return owner.moveCards(card, Zone.BATTLEFIELD, source, game);
    }
}
