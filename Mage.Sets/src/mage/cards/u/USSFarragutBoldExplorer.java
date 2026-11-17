package mage.cards.u;

import mage.abilities.Ability;
import mage.abilities.common.LandfallAbility;
import mage.abilities.common.delayed.AtTheBeginOfNextEndStepDelayedTriggeredAbility;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.GainAbilityTargetEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.HasteAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.common.TargetCardInHand;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class USSFarragutBoldExplorer extends CardImpl {

    public USSFarragutBoldExplorer(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{6}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.SPACECRAFT);

        // Landfall -- Whenever a land you control enters, you may put a creature card from your hand onto the
        // battlefield. That creature gains haste. At the beginning of the next end step, put it on the bottom of your
        // library. When you do, draw a card.
        this.addAbility(new LandfallAbility(new USSFarragutBoldExplorerEffect()));

        // Station
        this.addAbility(new StationAbility());

        // STATION 7+
        // Flying
        // 4/4
        this.addAbility(new StationLevelAbility(7)
                .withLevelAbility(FlyingAbility.getInstance())
                .withPT(4, 4));
    }

    private USSFarragutBoldExplorer(final USSFarragutBoldExplorer card) {
        super(card);
    }

    @Override
    public USSFarragutBoldExplorer copy() {
        return new USSFarragutBoldExplorer(this);
    }
}

class USSFarragutBoldExplorerEffect extends OneShotEffect {

    USSFarragutBoldExplorerEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "you may put a creature card from your hand onto the battlefield. That creature gains haste. "
                + "At the beginning of the next end step, put it on the bottom of your library. When you do, draw a card";
    }

    private USSFarragutBoldExplorerEffect(final USSFarragutBoldExplorerEffect effect) {
        super(effect);
    }

    @Override
    public USSFarragutBoldExplorerEffect copy() {
        return new USSFarragutBoldExplorerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null || controller.getHand().count(StaticFilters.FILTER_CARD_CREATURE, game) == 0
                || !controller.chooseUse(Outcome.PutCreatureInPlay, "Put a creature card from your hand onto the battlefield?", source, game)) {
            return false;
        }
        TargetCardInHand target = new TargetCardInHand(StaticFilters.FILTER_CARD_CREATURE);
        controller.choose(Outcome.PutCreatureInPlay, target, source, game);
        Card card = game.getCard(target.getFirstTarget());
        if (card == null || !controller.moveCards(card, Zone.BATTLEFIELD, source, game)) {
            return false;
        }
        Permanent permanent = game.getPermanent(card.getId());
        if (permanent == null) {
            return true;
        }
        ContinuousEffect haste = new GainAbilityTargetEffect(HasteAbility.getInstance(), Duration.Custom);
        haste.setTargetPointer(new FixedTarget(permanent, game));
        game.addEffect(haste, source);
        OneShotEffect bottom = new USSFarragutBoldExplorerBottomEffect();
        bottom.setTargetPointer(new FixedTarget(permanent, game));
        game.addDelayedTriggeredAbility(new AtTheBeginOfNextEndStepDelayedTriggeredAbility(bottom), source);
        return true;
    }
}

class USSFarragutBoldExplorerBottomEffect extends OneShotEffect {

    USSFarragutBoldExplorerBottomEffect() {
        super(Outcome.Benefit);
        staticText = "put it on the bottom of your library. When you do, draw a card";
    }

    private USSFarragutBoldExplorerBottomEffect(final USSFarragutBoldExplorerBottomEffect effect) {
        super(effect);
    }

    @Override
    public USSFarragutBoldExplorerBottomEffect copy() {
        return new USSFarragutBoldExplorerBottomEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Permanent permanent = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (controller == null || permanent == null) {
            return false; // it already left the battlefield: nothing to put on the bottom, so no draw
        }
        Player owner = game.getPlayer(permanent.getOwnerId());
        if (owner != null && owner.putCardsOnBottomOfLibrary(permanent, game, source)) {
            controller.drawCards(1, source, game);
        }
        return true;
    }
}
