package mage.watchers.common;

import mage.constants.WatcherScope;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.watchers.Watcher;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Counts the dilemmas each player faced this turn (700.17, Star Trek: choosing one or more modes for a modal
 * spell or ability). Fed by GameEvent.EventType.FACED_DILEMMA. DARRELLBEST-FORK.
 */
public class FacedDilemmaWatcher extends Watcher {

    private final Map<UUID, Integer> dilemmasThisTurn = new HashMap<>();

    public FacedDilemmaWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() == GameEvent.EventType.FACED_DILEMMA && event.getPlayerId() != null) {
            dilemmasThisTurn.merge(event.getPlayerId(), 1, Integer::sum);
        }
    }

    @Override
    public void reset() {
        super.reset();
        dilemmasThisTurn.clear();
    }

    public static int getCount(UUID playerId, Game game) {
        FacedDilemmaWatcher watcher = game.getState().getWatcher(FacedDilemmaWatcher.class);
        return watcher == null ? 0 : watcher.dilemmasThisTurn.getOrDefault(playerId, 0);
    }
}
