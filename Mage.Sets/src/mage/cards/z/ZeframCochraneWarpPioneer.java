package mage.cards.z;

import mage.MageIdentifier;
import mage.MageInt;
import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.AsThoughEffectImpl;
import mage.abilities.effects.common.EntersWithCountersControlledEffect;
import mage.abilities.effects.common.continuous.LookAtTopCardOfLibraryAnyTimeEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AsThoughEffectType;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.filter.common.FilterControlledPermanent;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.watchers.common.OnceEachTurnCastWatcher;

import java.util.UUID;

public final class ZeframCochraneWarpPioneer extends CardImpl {

    private static final FilterControlledPermanent spacecraft = new FilterControlledPermanent(SubType.SPACECRAFT, "Spacecraft you control");

    public ZeframCochraneWarpPioneer(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.PILOT);
        this.power = new MageInt(2);
        this.toughness = new MageInt(3);

        // You may look at the top card of your library any time.
        this.addAbility(new SimpleStaticAbility(new LookAtTopCardOfLibraryAnyTimeEffect()));

        // Once during each of your turns, you may cast an artifact spell from the top of your library.
        this.addAbility(new SimpleStaticAbility(new ZeframCochraneWarpPioneerEffect())
                .setIdentifier(MageIdentifier.OnceEachTurnCastWatcher)
                .addHint(OnceEachTurnCastWatcher.getHint()), new OnceEachTurnCastWatcher());

        // Spacecraft you control enter with two additional charge counters on them.
        this.addAbility(new SimpleStaticAbility(new EntersWithCountersControlledEffect(
                spacecraft, CounterType.CHARGE.createInstance(2), false
        ).setText("Spacecraft you control enter with two additional charge counters on them")));
    }

    private ZeframCochraneWarpPioneer(final ZeframCochraneWarpPioneer card) {
        super(card);
    }

    @Override
    public ZeframCochraneWarpPioneer copy() {
        return new ZeframCochraneWarpPioneer(this);
    }
}

class ZeframCochraneWarpPioneerEffect extends AsThoughEffectImpl {

    ZeframCochraneWarpPioneerEffect() {
        super(AsThoughEffectType.CAST_FROM_NOT_OWN_HAND_ZONE, Duration.WhileOnBattlefield, Outcome.Benefit);
        staticText = "Once during each of your turns, you may cast an artifact spell from the top of your library";
    }

    private ZeframCochraneWarpPioneerEffect(final ZeframCochraneWarpPioneerEffect effect) {
        super(effect);
    }

    @Override
    public ZeframCochraneWarpPioneerEffect copy() {
        return new ZeframCochraneWarpPioneerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        return true;
    }

    @Override
    public boolean applies(UUID objectId, Ability source, UUID affectedControllerId, Game game) {
        throw new IllegalArgumentException("Wrong code usage: can't call applies method on empty affectedAbility");
    }

    @Override
    public boolean applies(UUID objectId, Ability affectedAbility, Ability source, Game game, UUID playerId) {
        Player controller = game.getPlayer(source.getControllerId());
        OnceEachTurnCastWatcher watcher = game.getState().getWatcher(OnceEachTurnCastWatcher.class);
        Permanent sourcePermanent = source.getSourcePermanentIfItStillExists(game);
        if (controller == null || sourcePermanent == null || watcher == null
                || !playerId.equals(source.getControllerId()) || !game.isActivePlayer(playerId)) {
            return false; // "during each of your turns"
        }
        if (watcher.isAbilityUsed(controller.getId(), new MageObjectReference(sourcePermanent, game))) {
            return false;
        }
        Card card = game.getCard(objectId);
        Card topCard = controller.getLibrary().getFromTop(game);
        if (card == null || topCard == null || !topCard.getId().equals(card.getMainCard().getId())) {
            return false;
        }
        if (affectedAbility instanceof SpellAbility) {
            SpellAbility spellAbility = (SpellAbility) affectedAbility;
            if (spellAbility.getManaCosts().isEmpty()
                    || !spellAbility.spellCanBeActivatedNow(playerId, game).contains(MageIdentifier.Default)) {
                return false;
            }
            return spellAbility.getCharacteristics(game).isArtifact(game);
        }
        return false;
    }
}
