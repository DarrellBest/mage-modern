package mage.cards.q;

import mage.MageInt;
import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.common.AttacksTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.MenaceAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.choices.ChoiceColor;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.game.Game;
import mage.players.Player;

import java.util.UUID;

public final class QonoSQuartermaster extends CardImpl {

    public QonoSQuartermaster(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{R}");

        this.subtype.add(SubType.KLINGON);
        this.subtype.add(SubType.WARRIOR);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Menace
        this.addAbility(new MenaceAbility());

        // Whenever this creature attacks, add one mana of any color. Until end of turn, you don't lose this mana as steps and phases end.
        this.addAbility(new AttacksTriggeredAbility(new QonoSQuartermasterEffect()));
    }

    private QonoSQuartermaster(final QonoSQuartermaster card) {
        super(card);
    }

    @Override
    public QonoSQuartermaster copy() {
        return new QonoSQuartermaster(this);
    }
}

class QonoSQuartermasterEffect extends OneShotEffect {

    QonoSQuartermasterEffect() {
        super(Outcome.PutManaInPool);
        staticText = "add one mana of any color. Until end of turn, you don't lose this mana as steps and phases end";
    }

    private QonoSQuartermasterEffect(final QonoSQuartermasterEffect effect) {
        super(effect);
    }

    @Override
    public QonoSQuartermasterEffect copy() {
        return new QonoSQuartermasterEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        ChoiceColor choice = new ChoiceColor(true, "Choose a color of mana to add");
        if (!player.choose(Outcome.PutManaInPool, choice, game) || choice.getMana(1) == null) {
            return false;
        }
        Mana mana = choice.getMana(1);
        player.getManaPool().addMana(mana, game, source, true);
        return true;
    }
}
