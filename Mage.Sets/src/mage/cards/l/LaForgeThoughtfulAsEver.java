package mage.cards.l;

import mage.MageInt;
import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.dynamicvalue.DynamicValue;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.WatcherScope;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.permanent.token.FaerieToken;
import mage.watchers.Watcher;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LaForgeThoughtfulAsEver extends CardImpl {

    public LaForgeThoughtfulAsEver(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(4);

        // {T}: For each permanent you control that you've put a counter on this turn, create a 1/1 blue Faerie creature token with flying.
        Ability ability = new SimpleActivatedAbility(new CreateTokenEffect(new FaerieToken(), LaForgeThoughtfulAsEverValue.instance)
                .setText("for each permanent you control that you've put a counter on this turn, "
                        + "create a 1/1 blue Faerie creature token with flying"), new TapSourceCost());
        ability.addWatcher(new LaForgeThoughtfulAsEverWatcher());
        this.addAbility(ability);
    }

    private LaForgeThoughtfulAsEver(final LaForgeThoughtfulAsEver card) {
        super(card);
    }

    @Override
    public LaForgeThoughtfulAsEver copy() {
        return new LaForgeThoughtfulAsEver(this);
    }
}

enum LaForgeThoughtfulAsEverValue implements DynamicValue {
    instance;

    @Override
    public int calculate(Game game, Ability sourceAbility, Effect effect) {
        LaForgeThoughtfulAsEverWatcher watcher = game.getState().getWatcher(LaForgeThoughtfulAsEverWatcher.class);
        if (watcher == null) {
            return 0;
        }
        int count = 0;
        for (MageObjectReference mor : watcher.get(sourceAbility.getControllerId())) {
            Permanent permanent = mor.getPermanent(game);
            if (permanent != null && permanent.isControlledBy(sourceAbility.getControllerId())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public LaForgeThoughtfulAsEverValue copy() {
        return this;
    }

    @Override
    public String getMessage() {
        return "permanent you control that you've put a counter on this turn";
    }

    @Override
    public String toString() {
        return "1";
    }
}

/** Per player: the permanents that player put a counter on this turn. */
class LaForgeThoughtfulAsEverWatcher extends Watcher {

    private final Map<UUID, Set<MageObjectReference>> map = new HashMap<>();

    LaForgeThoughtfulAsEverWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() != GameEvent.EventType.COUNTERS_ADDED || event.getPlayerId() == null || event.getAmount() < 1) {
            return;
        }
        Permanent permanent = game.getPermanent(event.getTargetId());
        if (permanent != null) {
            map.computeIfAbsent(event.getPlayerId(), k -> new HashSet<>()).add(new MageObjectReference(permanent, game));
        }
    }

    @Override
    public void reset() {
        super.reset();
        map.clear();
    }

    Set<MageObjectReference> get(UUID playerId) {
        return map.getOrDefault(playerId, new HashSet<>());
    }
}
