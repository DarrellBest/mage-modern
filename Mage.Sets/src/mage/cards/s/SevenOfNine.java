package mage.cards.s;

import mage.MageInt;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;

import java.util.UUID;

public final class SevenOfNine extends CardImpl {

    public SevenOfNine(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{2}{U}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.SCIENTIST);
        this.power = new MageInt(2);
        this.toughness = new MageInt(4);

        // Whenever you face a dilemma, draw a card.
        this.addAbility(new FaceDilemmaTriggeredAbility(new DrawCardSourceControllerEffect(1)));
    }

    private SevenOfNine(final SevenOfNine card) {
        super(card);
    }

    @Override
    public SevenOfNine copy() {
        return new SevenOfNine(this);
    }
}
