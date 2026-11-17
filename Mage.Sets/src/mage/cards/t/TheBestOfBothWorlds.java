package mage.cards.t;

import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.common.SagaAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.BoostControlledEffect;
import mage.abilities.effects.keyword.AssimilateEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.filter.common.FilterCreaturePermanent;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetPermanent;
import mage.util.CardUtil;

import java.util.UUID;

public final class TheBestOfBothWorlds extends CardImpl {

    private static final FilterCreaturePermanent filter
            = new FilterCreaturePermanent("nonartifact creature an opponent controls");

    static {
        filter.add(Predicates.not(CardType.ARTIFACT.getPredicate()));
        filter.add(mage.constants.TargetController.OPPONENT.getControllerPredicate());
    }

    public TheBestOfBothWorlds(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{4}{W}{U}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after III.)
        SagaAbility sagaAbility = new SagaAbility(this);

        // I -- Assimilate target nonartifact creature an opponent controls.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I, new TheBestOfBothWorldsAssimilateEffect(),
                new TargetPermanent(filter));

        // II -- Artifact creatures you control get +2/+2 until end of turn.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_II, new BoostControlledEffect(
                2, 2, Duration.EndOfTurn, StaticFilters.FILTER_PERMANENTS_ARTIFACT_CREATURE));

        // III -- Exile the Borg assimilated with this Saga, then return it to the battlefield under its owner's control.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_III, new TheBestOfBothWorldsReturnEffect());

        this.addAbility(sagaAbility);
    }

    private TheBestOfBothWorlds(final TheBestOfBothWorlds card) {
        super(card);
    }

    @Override
    public TheBestOfBothWorlds copy() {
        return new TheBestOfBothWorlds(this);
    }

    // the Borg this Saga (this object, by zone change counter) assimilated
    static String key(Game game, Ability source) {
        return CardUtil.getObjectZoneString("TheBestOfBothWorlds_assimilated",
                source.getSourceId(), game, game.getState().getZoneChangeCounter(source.getSourceId()), false);
    }
}

class TheBestOfBothWorldsAssimilateEffect extends OneShotEffect {

    TheBestOfBothWorldsAssimilateEffect() {
        super(Outcome.GainControl);
        staticText = "assimilate target nonartifact creature an opponent controls";
    }

    private TheBestOfBothWorldsAssimilateEffect(final TheBestOfBothWorldsAssimilateEffect effect) {
        super(effect);
    }

    @Override
    public TheBestOfBothWorldsAssimilateEffect copy() {
        return new TheBestOfBothWorldsAssimilateEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent permanent = AssimilateEffect.assimilate(
                game.getPermanent(getTargetPointer().getFirst(game, source)), source, game);
        if (permanent == null) {
            return false;
        }
        game.getState().setValue(TheBestOfBothWorlds.key(game, source), new MageObjectReference(permanent, game));
        return true;
    }
}

class TheBestOfBothWorldsReturnEffect extends OneShotEffect {

    TheBestOfBothWorldsReturnEffect() {
        super(Outcome.Neutral);
        staticText = "exile the Borg assimilated with this Saga, then return it to the battlefield under its owner's control";
    }

    private TheBestOfBothWorldsReturnEffect(final TheBestOfBothWorldsReturnEffect effect) {
        super(effect);
    }

    @Override
    public TheBestOfBothWorldsReturnEffect copy() {
        return new TheBestOfBothWorldsReturnEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Object value = game.getState().getValue(TheBestOfBothWorlds.key(game, source));
        if (controller == null || !(value instanceof MageObjectReference)) {
            return false;
        }
        Permanent borg = ((MageObjectReference) value).getPermanent(game);
        if (borg == null) {
            return false; // it left the battlefield since: it's a new object now
        }
        controller.moveCards(borg, Zone.EXILED, source, game);
        game.processAction();
        mage.cards.Card card = game.getCard(borg.getId());
        if (card != null && game.getState().getZone(card.getId()) == Zone.EXILED) {
            Player owner = game.getPlayer(card.getOwnerId());
            if (owner != null) {
                owner.moveCards(card, Zone.BATTLEFIELD, source, game);
            }
        }
        return true;
    }
}
