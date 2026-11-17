package mage.cards.u;

import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldOrDiesSourceTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.players.Player;

import java.util.UUID;

public final class USSEnterpriseCTimeTossed extends CardImpl {

    public USSEnterpriseCTimeTossed(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{5}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.SPACECRAFT);

        // When U.S.S. Enterprise-C enters or dies, you may discard your hand. If you do, draw a card for each card discarded this way.
        this.addAbility(new EntersBattlefieldOrDiesSourceTriggeredAbility(new USSEnterpriseCTimeTossedEffect(), false));

        // Station
        this.addAbility(new StationAbility());

        // STATION 3+
        // Flying
        // 6/6
        this.addAbility(new StationLevelAbility(3)
                .withLevelAbility(FlyingAbility.getInstance())
                .withPT(6, 6));
    }

    private USSEnterpriseCTimeTossed(final USSEnterpriseCTimeTossed card) {
        super(card);
    }

    @Override
    public USSEnterpriseCTimeTossed copy() {
        return new USSEnterpriseCTimeTossed(this);
    }
}

class USSEnterpriseCTimeTossedEffect extends OneShotEffect {

    USSEnterpriseCTimeTossedEffect() {
        super(Outcome.DrawCard);
        staticText = "you may discard your hand. If you do, draw a card for each card discarded this way";
    }

    private USSEnterpriseCTimeTossedEffect(final USSEnterpriseCTimeTossedEffect effect) {
        super(effect);
    }

    @Override
    public USSEnterpriseCTimeTossedEffect copy() {
        return new USSEnterpriseCTimeTossedEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null || controller.getHand().isEmpty()
                || !controller.chooseUse(Outcome.DrawCard, "Discard your hand and draw that many?", source, game)) {
            return false;
        }
        int discarded = controller.discard(controller.getHand(), false, source, game).size();
        if (discarded > 0) {
            controller.drawCards(discarded, source, game);
        }
        return true;
    }
}
