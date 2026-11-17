package mage.cards.h;

import mage.abilities.Ability;
import mage.abilities.common.DealsCombatDamageToAPlayerTriggeredAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.search.SearchLibraryPutInHandEffect;
import mage.abilities.effects.common.turn.AddExtraTurnControllerEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.WatcherScope;
import mage.filter.FilterCard;
import mage.filter.common.FilterControlledPermanent;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.players.Player;
import mage.target.common.TargetCardInLibrary;
import mage.watchers.Watcher;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HMSBountyUnlikelyTimeship extends CardImpl {

    private static final FilterCard whaleCard = new FilterCard("a Whale card");

    static {
        whaleCard.add(SubType.WHALE.getPredicate());
    }

    public HMSBountyUnlikelyTimeship(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{4}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.SPACECRAFT);

        // When H.M.S. Bounty enters, you may search your library for a Whale card, reveal it, put it into your hand, then shuffle.
        this.addAbility(new EntersBattlefieldTriggeredAbility(
                new SearchLibraryPutInHandEffect(new TargetCardInLibrary(whaleCard), true), true
        ));

        // Station
        this.addAbility(new StationAbility());

        // STATION 8+
        // Flying
        // Whenever H.M.S. Bounty deals combat damage to a player, draw a card. If you control a Whale and haven't
        // begun an extra turn this game, take an extra turn after this one.
        // 3/4
        Ability damage = new DealsCombatDamageToAPlayerTriggeredAbility(new HMSBountyUnlikelyTimeshipEffect());
        damage.addWatcher(new HMSBountyUnlikelyTimeshipWatcher());
        this.addAbility(new StationLevelAbility(8)
                .withLevelAbility(FlyingAbility.getInstance())
                .withLevelAbility(damage)
                .withPT(3, 4));
    }

    private HMSBountyUnlikelyTimeship(final HMSBountyUnlikelyTimeship card) {
        super(card);
    }

    @Override
    public HMSBountyUnlikelyTimeship copy() {
        return new HMSBountyUnlikelyTimeship(this);
    }
}

class HMSBountyUnlikelyTimeshipEffect extends OneShotEffect {

    private static final FilterControlledPermanent whale = new FilterControlledPermanent(SubType.WHALE, "a Whale");

    HMSBountyUnlikelyTimeshipEffect() {
        super(Outcome.ExtraTurn);
        staticText = "draw a card. If you control a Whale and haven't begun an extra turn this game, "
                + "take an extra turn after this one";
    }

    private HMSBountyUnlikelyTimeshipEffect(final HMSBountyUnlikelyTimeshipEffect effect) {
        super(effect);
    }

    @Override
    public HMSBountyUnlikelyTimeshipEffect copy() {
        return new HMSBountyUnlikelyTimeshipEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        controller.drawCards(1, source, game);
        if (game.getBattlefield().contains(whale, source, game, 1)
                && !HMSBountyUnlikelyTimeshipWatcher.hasBegunExtraTurn(controller.getId(), game)) {
            new AddExtraTurnControllerEffect().apply(game, source);
        }
        return true;
    }
}

/** Players who have begun an extra turn this game (never reset). */
class HMSBountyUnlikelyTimeshipWatcher extends Watcher {

    private final Set<UUID> players = new HashSet<>();

    HMSBountyUnlikelyTimeshipWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() == GameEvent.EventType.EXTRA_TURN && event.getPlayerId() != null) {
            players.add(event.getPlayerId());
        }
    }

    @Override
    public void reset() {
        // "this game": keep across turns
    }

    static boolean hasBegunExtraTurn(UUID playerId, Game game) {
        HMSBountyUnlikelyTimeshipWatcher watcher = game.getState().getWatcher(HMSBountyUnlikelyTimeshipWatcher.class);
        return watcher != null && watcher.players.contains(playerId);
    }
}
