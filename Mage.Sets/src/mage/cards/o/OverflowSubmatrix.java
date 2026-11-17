package mage.cards.o;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.CastFromGraveyardOnceDuringEachOfYourTurnAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.WatcherScope;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.filter.common.FilterCreatureCard;
import mage.game.Game;
import mage.game.events.EntersTheBattlefieldEvent;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.watchers.Watcher;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OverflowSubmatrix extends CardImpl {

    private static final FilterCreatureCard artifactCreature = new FilterCreatureCard("an artifact creature spell");

    static {
        artifactCreature.add(CardType.ARTIFACT.getPredicate());
    }

    public OverflowSubmatrix(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{2}{B}");

        this.subtype.add(SubType.BORG);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Once during each of your turns, you may cast an artifact creature spell from your graveyard. If you cast a
        // spell this way, that artifact creature enters with a finality counter on it.
        Ability ability = new CastFromGraveyardOnceDuringEachOfYourTurnAbility(artifactCreature);
        ability.addWatcher(new OverflowSubmatrixWatcher());
        this.addAbility(ability);
        this.addAbility(new SimpleStaticAbility(new OverflowSubmatrixEffect()).setRuleVisible(false));
    }

    private OverflowSubmatrix(final OverflowSubmatrix card) {
        super(card);
    }

    @Override
    public OverflowSubmatrix copy() {
        return new OverflowSubmatrix(this);
    }
}

/** Cards currently being cast from a graveyard with an Overflow Submatrix's permission. */
class OverflowSubmatrixWatcher extends Watcher {

    final Set<UUID> fromSubmatrix = new HashSet<>();

    OverflowSubmatrixWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() == GameEvent.EventType.ZONE_CHANGE) {
            // the spell left the stack some other way (countered, exiled...): forget it
            ZoneChangeEvent zce = (ZoneChangeEvent) event;
            if (zce.getFromZone() == Zone.STACK && zce.getToZone() != Zone.BATTLEFIELD) {
                fromSubmatrix.remove(zce.getTargetId());
            }
            return;
        }
        if (event.getType() != GameEvent.EventType.SPELL_CAST || event.getApprovingObject() == null) {
            return;
        }
        Ability approving = event.getApprovingObject().getApprovingAbility();
        if (!(approving instanceof CastFromGraveyardOnceDuringEachOfYourTurnAbility)) {
            return;
        }
        Permanent submatrix = game.getPermanentOrLKIBattlefield(approving.getSourceId());
        if (submatrix == null || !"Overflow Submatrix".equals(submatrix.getName())) {
            return;
        }
        Spell spell = game.getSpell(event.getTargetId());
        if (spell != null) {
            fromSubmatrix.add(spell.getSourceId());
        }
    }

    @Override
    public void reset() {
        // keep: the spell may resolve on a later step than the cast
    }
}

class OverflowSubmatrixEffect extends ReplacementEffectImpl {

    OverflowSubmatrixEffect() {
        super(Duration.WhileOnBattlefield, Outcome.Detriment);
    }

    private OverflowSubmatrixEffect(final OverflowSubmatrixEffect effect) {
        super(effect);
    }

    @Override
    public OverflowSubmatrixEffect copy() {
        return new OverflowSubmatrixEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ENTERS_THE_BATTLEFIELD;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        OverflowSubmatrixWatcher watcher = game.getState().getWatcher(OverflowSubmatrixWatcher.class);
        Permanent permanent = ((EntersTheBattlefieldEvent) event).getTarget();
        return watcher != null && permanent != null && watcher.fromSubmatrix.contains(permanent.getId());
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        Permanent permanent = ((EntersTheBattlefieldEvent) event).getTarget();
        OverflowSubmatrixWatcher watcher = game.getState().getWatcher(OverflowSubmatrixWatcher.class);
        if (permanent != null) {
            permanent.addCounters(CounterType.FINALITY.createInstance(), source.getControllerId(), source, game, event.getAppliedEffects());
            if (watcher != null) {
                watcher.fromSubmatrix.remove(permanent.getId());
            }
        }
        return false;
    }
}
