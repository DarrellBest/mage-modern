package mage.abilities.effects.keyword;

import mage.abilities.Ability;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.AddCardTypeTargetEffect;
import mage.abilities.effects.common.continuous.BecomesCreatureTypeTargetEffect;
import mage.abilities.effects.common.continuous.GainControlTargetEffect;
import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

/**
 * Assimilate (701.72, Star Trek keyword action). DARRELLBEST-FORK.
 * <p>
 * To assimilate a creature on the battlefield: gain control of it, put a +1/+1 counter on it, and it becomes a Borg
 * artifact creature that loses its other creature types. To assimilate a card elsewhere: put it onto the battlefield
 * under your control with a +1/+1 counter on it, and it becomes the same. Supertypes, other card types and
 * non-creature subtypes are kept. The changes last for as long as it stays on the battlefield.
 * <p>
 * Used as an effect, it assimilates every target of its target pointer (permanents or cards).
 */
public class AssimilateEffect extends OneShotEffect {

    public AssimilateEffect() {
        super(Outcome.GainControl);
    }

    protected AssimilateEffect(final AssimilateEffect effect) {
        super(effect);
    }

    @Override
    public AssimilateEffect copy() {
        return new AssimilateEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        boolean result = false;
        for (UUID id : getTargetPointer().getTargets(game, source)) {
            Permanent permanent = game.getPermanent(id);
            if (permanent != null) {
                result |= assimilate(permanent, source, game) != null;
                continue;
            }
            Card card = game.getCard(id);
            if (card != null) {
                result |= assimilate(card, source, game) != null;
            }
        }
        return result;
    }

    @Override
    public String getText(mage.abilities.Mode mode) {
        if (staticText != null && !staticText.isEmpty()) {
            return staticText;
        }
        return "assimilate " + getTargetPointer().describeTargets(mode.getTargets(), "that creature");
    }

    /**
     * Assimilate a creature on the battlefield for the controller of {@code source}.
     */
    public static Permanent assimilate(Permanent permanent, Ability source, Game game) {
        if (permanent == null || !permanent.isCreature(game)) {
            return null;
        }
        UUID you = source.getControllerId();
        if (!permanent.isControlledBy(you)) {
            ContinuousEffect control = new GainControlTargetEffect(Duration.Custom, true, you);
            control.setTargetPointer(new FixedTarget(permanent, game));
            game.addEffect(control, source);
            game.processAction();
        }
        permanent.addCounters(CounterType.P1P1.createInstance(), you, source, game);
        becomeBorg(permanent, source, game);
        return permanent;
    }

    /**
     * Assimilate a card that isn't on the battlefield (graveyard, exile, library, hand) for the controller of
     * {@code source}. Returns the new permanent, or null if it didn't get onto the battlefield.
     */
    public static Permanent assimilate(Card card, Ability source, Game game) {
        Player controller = game.getPlayer(source.getControllerId());
        if (card == null || controller == null) {
            return null;
        }
        Counters counters = new Counters();
        counters.addCounter(CounterType.P1P1.createInstance());
        game.setEnterWithCounters(card.getId(), counters);
        if (!controller.moveCards(card, Zone.BATTLEFIELD, source, game)) {
            game.setEnterWithCounters(card.getId(), null);
            return null;
        }
        Permanent permanent = game.getPermanent(card.getId());
        if (permanent == null) {
            return null;
        }
        becomeBorg(permanent, source, game);
        return permanent;
    }

    private static void becomeBorg(Permanent permanent, Ability source, Game game) {
        ContinuousEffect types = new AddCardTypeTargetEffect(Duration.Custom, CardType.ARTIFACT, CardType.CREATURE);
        types.setTargetPointer(new FixedTarget(permanent, game));
        game.addEffect(types, source);
        ContinuousEffect borg = new BecomesCreatureTypeTargetEffect(Duration.Custom, SubType.BORG, true);
        borg.setTargetPointer(new FixedTarget(permanent, game));
        game.addEffect(borg, source);
        game.processAction();
    }
}
