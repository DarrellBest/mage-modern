package mage.cards.w;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.delayed.AtTheBeginOfNextEndStepDelayedTriggeredAbility;
import mage.constants.TargetController;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.SacrificeTargetEffect;
import mage.abilities.triggers.BeginningOfCombatTriggeredAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.targetpointer.FixedTargets;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WillRikerFirstInTheField extends CardImpl {

    public WillRikerFirstInTheField(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(4);
        this.toughness = new MageInt(3);

        // At the beginning of combat on your turn, reveal cards from the top of your library until you reveal two land
        // cards. Put those land cards onto the battlefield and the rest on the bottom of your library in a random order.
        // Sacrifice those lands at the beginning of your next end step.
        this.addAbility(new BeginningOfCombatTriggeredAbility(new WillRikerFirstInTheFieldEffect()));
    }

    private WillRikerFirstInTheField(final WillRikerFirstInTheField card) {
        super(card);
    }

    @Override
    public WillRikerFirstInTheField copy() {
        return new WillRikerFirstInTheField(this);
    }
}

class WillRikerFirstInTheFieldEffect extends OneShotEffect {

    WillRikerFirstInTheFieldEffect() {
        super(Outcome.PutLandInPlay);
        staticText = "reveal cards from the top of your library until you reveal two land cards. Put those land cards "
                + "onto the battlefield and the rest on the bottom of your library in a random order. "
                + "Sacrifice those lands at the beginning of your next end step";
    }

    private WillRikerFirstInTheFieldEffect(final WillRikerFirstInTheFieldEffect effect) {
        super(effect);
    }

    @Override
    public WillRikerFirstInTheFieldEffect copy() {
        return new WillRikerFirstInTheFieldEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        Cards revealed = new CardsImpl();
        Cards lands = new CardsImpl();
        for (Card card : controller.getLibrary().getCards(game)) {
            revealed.add(card);
            if (card.isLand(game)) {
                lands.add(card);
                if (lands.size() == 2) {
                    break;
                }
            }
        }
        if (revealed.isEmpty()) {
            return true;
        }
        controller.revealCards(source, revealed, game);
        controller.moveCards(lands, Zone.BATTLEFIELD, source, game);
        revealed.removeAll(lands);
        controller.putCardsOnBottomOfLibrary(revealed, game, source, false);
        List<Permanent> entered = new ArrayList<>();
        for (UUID id : lands) {
            Permanent permanent = game.getPermanent(id);
            if (permanent != null) {
                entered.add(permanent);
            }
        }
        if (!entered.isEmpty()) {
            SacrificeTargetEffect sacrifice = new SacrificeTargetEffect("sacrifice those lands");
            sacrifice.setTargetPointer(new FixedTargets(entered, game));
            // this trigger is at the beginning of combat on your turn, so your next end step is this turn's
            game.addDelayedTriggeredAbility(new AtTheBeginOfNextEndStepDelayedTriggeredAbility(sacrifice, TargetController.YOU), source);
        }
        return true;
    }
}
