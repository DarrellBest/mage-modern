package mage.cards.p;

import mage.abilities.Ability;
import mage.abilities.condition.common.SourceHasCounterCondition;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.RestrictionEffect;
import mage.abilities.effects.common.WinGameSourceControllerEffect;
import mage.abilities.triggers.BeginningOfCombatTriggeredAbility;
import mage.abilities.triggers.BeginningOfUpkeepTriggeredAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.TargetController;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.UUID;

public final class PeaceInOurTime extends CardImpl {

    public PeaceInOurTime(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{3}{W}");

        // At the beginning of combat on each player's turn, that player may draw a card. If they do, they can't
        // attack this turn and you put a peace counter on this enchantment.
        this.addAbility(new BeginningOfCombatTriggeredAbility(TargetController.ANY, new PeaceInOurTimeEffect(), false));

        // At the beginning of your upkeep, if this enchantment has twelve or more peace counters on it, you win the game.
        this.addAbility(new BeginningOfUpkeepTriggeredAbility(new WinGameSourceControllerEffect())
                .withInterveningIf(new SourceHasCounterCondition(CounterType.PEACE, 12)));
    }

    private PeaceInOurTime(final PeaceInOurTime card) {
        super(card);
    }

    @Override
    public PeaceInOurTime copy() {
        return new PeaceInOurTime(this);
    }
}

class PeaceInOurTimeEffect extends OneShotEffect {

    PeaceInOurTimeEffect() {
        super(Outcome.Benefit);
        staticText = "that player may draw a card. If they do, they can't attack this turn and you put a peace counter on this enchantment";
    }

    private PeaceInOurTimeEffect(final PeaceInOurTimeEffect effect) {
        super(effect);
    }

    @Override
    public PeaceInOurTimeEffect copy() {
        return new PeaceInOurTimeEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(game.getActivePlayerId());
        if (player == null || !player.chooseUse(Outcome.DrawCard,
                "Draw a card? If you do, you can't attack this turn.", source, game)) {
            return false;
        }
        if (player.drawCards(1, source, game) < 1) {
            return false;
        }
        game.addEffect(new PeaceInOurTimeCantAttackEffect(player.getId()), source);
        Permanent peace = source.getSourcePermanentIfItStillExists(game);
        if (peace != null) {
            peace.addCounters(CounterType.PEACE.createInstance(), source.getControllerId(), source, game);
        }
        return true;
    }
}

class PeaceInOurTimeCantAttackEffect extends RestrictionEffect {

    private final UUID playerId;

    PeaceInOurTimeCantAttackEffect(UUID playerId) {
        super(Duration.EndOfTurn);
        this.playerId = playerId;
    }

    private PeaceInOurTimeCantAttackEffect(final PeaceInOurTimeCantAttackEffect effect) {
        super(effect);
        this.playerId = effect.playerId;
    }

    @Override
    public PeaceInOurTimeCantAttackEffect copy() {
        return new PeaceInOurTimeCantAttackEffect(this);
    }

    @Override
    public boolean applies(Permanent permanent, Ability source, Game game) {
        return permanent.isControlledBy(playerId);
    }

    @Override
    public boolean canAttack(Permanent attacker, UUID defenderId, Ability source, Game game, boolean canUseChooseDialogs) {
        return false;
    }
}
