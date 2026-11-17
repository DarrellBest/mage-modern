package mage.cards.g;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.MenaceAbility;
import mage.abilities.triggers.BeginningOfEndStepTriggeredAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.TargetController;
import mage.game.Game;
import mage.players.Player;
import mage.watchers.common.PlayerLostLifeWatcher;

import java.util.UUID;

public final class GulDukatColonialPrefect extends CardImpl {

    public GulDukatColonialPrefect(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{4}{B}{B}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.CARDASSIAN);
        this.subtype.add(SubType.ROGUE);
        this.power = new MageInt(6);
        this.toughness = new MageInt(6);

        // Menace
        this.addAbility(new MenaceAbility());

        // At the beginning of each end step, each opponent loses life equal to the life that player lost this turn.
        // You gain life equal to the life lost this way.
        this.addAbility(new BeginningOfEndStepTriggeredAbility(
                TargetController.ANY, new GulDukatColonialPrefectEffect(), false
        ));
    }

    private GulDukatColonialPrefect(final GulDukatColonialPrefect card) {
        super(card);
    }

    @Override
    public GulDukatColonialPrefect copy() {
        return new GulDukatColonialPrefect(this);
    }
}

class GulDukatColonialPrefectEffect extends OneShotEffect {

    GulDukatColonialPrefectEffect() {
        super(Outcome.Damage);
        staticText = "each opponent loses life equal to the life that player lost this turn. "
                + "You gain life equal to the life lost this way";
    }

    private GulDukatColonialPrefectEffect(final GulDukatColonialPrefectEffect effect) {
        super(effect);
    }

    @Override
    public GulDukatColonialPrefectEffect copy() {
        return new GulDukatColonialPrefectEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        PlayerLostLifeWatcher watcher = game.getState().getWatcher(PlayerLostLifeWatcher.class);
        if (controller == null || watcher == null) {
            return false;
        }
        // read every opponent's total first, so one loss can't feed into another's count
        int total = 0;
        java.util.Map<UUID, Integer> lost = new java.util.HashMap<>();
        for (UUID opponentId : game.getOpponents(source.getControllerId())) {
            lost.put(opponentId, watcher.getLifeLost(opponentId));
        }
        for (java.util.Map.Entry<UUID, Integer> e : lost.entrySet()) {
            Player opponent = game.getPlayer(e.getKey());
            if (opponent != null && e.getValue() > 0) {
                total += opponent.loseLife(e.getValue(), game, source, false);
            }
        }
        if (total > 0) {
            controller.gainLife(total, game, source);
        }
        return true;
    }
}
