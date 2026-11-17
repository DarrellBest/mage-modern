package mage.cards.f;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.WatcherScope;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.players.Player;
import mage.target.TargetCard;
import mage.watchers.Watcher;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FirstContact extends CardImpl {

    public FirstContact(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.INSTANT}, "{1}{G}");

        // Mill four cards. You may put a permanent card from among them into your hand. If this spell is the first
        // spell you've cast this game, you gain 2 life.
        this.getSpellAbility().addEffect(new FirstContactEffect());
        this.getSpellAbility().addWatcher(new FirstContactWatcher());
    }

    private FirstContact(final FirstContact card) {
        super(card);
    }

    @Override
    public FirstContact copy() {
        return new FirstContact(this);
    }
}

class FirstContactEffect extends OneShotEffect {

    FirstContactEffect() {
        super(Outcome.ReturnToHand);
        staticText = "mill four cards. You may put a permanent card from among them into your hand. "
                + "If this spell is the first spell you've cast this game, you gain 2 life";
    }

    private FirstContactEffect(final FirstContactEffect effect) {
        super(effect);
    }

    @Override
    public FirstContactEffect copy() {
        return new FirstContactEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        Cards milled = controller.millCards(4, source, game);
        milled.retainZone(Zone.GRAVEYARD, game);
        if (milled.count(StaticFilters.FILTER_CARD_PERMANENT, game) > 0) {
            TargetCard target = new TargetCard(0, 1, Zone.GRAVEYARD, StaticFilters.FILTER_CARD_PERMANENT);
            target.withNotTarget(true);
            controller.choose(Outcome.ReturnToHand, milled, target, source, game);
            Card card = game.getCard(target.getFirstTarget());
            if (card != null) {
                controller.moveCards(card, Zone.HAND, source, game);
            }
        }
        if (FirstContactWatcher.spellsCastThisGame(controller.getId(), game) == 1) {
            controller.gainLife(2, game, source);
        }
        return true;
    }
}

/** Spells each player has cast this game (never reset). */
class FirstContactWatcher extends Watcher {

    private final Map<UUID, Integer> spells = new HashMap<>();

    FirstContactWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() == GameEvent.EventType.SPELL_CAST && event.getPlayerId() != null) {
            spells.merge(event.getPlayerId(), 1, Integer::sum);
        }
    }

    @Override
    public void reset() {
        // "this game": keep across turns
    }

    static int spellsCastThisGame(UUID playerId, Game game) {
        FirstContactWatcher watcher = game.getState().getWatcher(FirstContactWatcher.class);
        return watcher == null ? 0 : watcher.spells.getOrDefault(playerId, 0);
    }
}
