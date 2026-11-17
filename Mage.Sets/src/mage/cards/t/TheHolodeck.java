package mage.cards.t;

import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.AsThoughEffectImpl;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AsThoughEffectType;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SuperType;
import mage.constants.WatcherScope;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.watchers.Watcher;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Implemented for its graveyard half. "Outside the game" means a sideboard, which Commander games (where this
 * card is played here) don't have; XMage has no general "play from outside the game" permission.
 */
public final class TheHolodeck extends CardImpl {

    public TheHolodeck(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{7}");

        this.supertype.add(SuperType.LEGENDARY);

        // Once during each of your turns, you may play a permanent card you own from outside the game or from your graveyard.
        Ability ability = new SimpleStaticAbility(new TheHolodeckEffect());
        ability.addWatcher(new TheHolodeckWatcher());
        this.addAbility(ability);
    }

    private TheHolodeck(final TheHolodeck card) {
        super(card);
    }

    @Override
    public TheHolodeck copy() {
        return new TheHolodeck(this);
    }
}

class TheHolodeckEffect extends AsThoughEffectImpl {

    TheHolodeckEffect() {
        super(AsThoughEffectType.PLAY_FROM_NOT_OWN_HAND_ZONE, Duration.WhileOnBattlefield, Outcome.PutCardInPlay);
        staticText = "Once during each of your turns, you may play a permanent card you own from outside the game or from your graveyard";
    }

    private TheHolodeckEffect(final TheHolodeckEffect effect) {
        super(effect);
    }

    @Override
    public TheHolodeckEffect copy() {
        return new TheHolodeckEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        return true;
    }

    @Override
    public boolean applies(UUID objectId, Ability source, UUID affectedControllerId, Game game) {
        if (!source.isControlledBy(affectedControllerId) || !game.isActivePlayer(affectedControllerId)) {
            return false;
        }
        Card card = game.getCard(objectId);
        if (card == null || !card.isOwnedBy(affectedControllerId) || !card.isPermanent(game)
                || game.getState().getZone(card.getMainCard().getId()) != Zone.GRAVEYARD) {
            return false;
        }
        Permanent holodeck = source.getSourcePermanentIfItStillExists(game);
        return holodeck != null && !TheHolodeckWatcher.usedThisTurn(new MageObjectReference(holodeck, game), game);
    }
}

/** Holodecks whose permission was used this turn (a card cast or a land played with it). */
class TheHolodeckWatcher extends Watcher {

    private final Set<MageObjectReference> used = new HashSet<>();

    TheHolodeckWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if ((event.getType() == GameEvent.EventType.SPELL_CAST || event.getType() == GameEvent.EventType.LAND_PLAYED)
                && event.getApprovingObject() != null) {
            MageObjectReference approving = event.getApprovingObject().getApprovingMageObjectReference();
            Permanent permanent = approving == null ? null : approving.getPermanent(game);
            if (permanent != null && "The Holodeck".equals(permanent.getName())) {
                used.add(approving);
            }
        }
    }

    @Override
    public void reset() {
        super.reset();
        used.clear();
    }

    static boolean usedThisTurn(MageObjectReference holodeck, Game game) {
        TheHolodeckWatcher watcher = game.getState().getWatcher(TheHolodeckWatcher.class);
        return watcher != null && watcher.used.contains(holodeck);
    }
}
