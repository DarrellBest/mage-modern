package mage.cards.e;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.effects.common.GainLifeEffect;
import mage.abilities.effects.common.LoseLifeOpponentsEffect;
import mage.abilities.keyword.MenaceAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;

import java.util.UUID;

public final class ElimGarakSimpleTailor extends CardImpl {

    public ElimGarakSimpleTailor(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.CARDASSIAN);
        this.subtype.add(SubType.ROGUE);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Menace
        this.addAbility(new MenaceAbility());

        // Whenever you face a dilemma, each opponent loses 2 life and you gain 2 life.
        Ability ability = new FaceDilemmaTriggeredAbility(new LoseLifeOpponentsEffect(2));
        ability.addEffect(new GainLifeEffect(2).concatBy("and"));
        this.addAbility(ability);
    }

    private ElimGarakSimpleTailor(final ElimGarakSimpleTailor card) {
        super(card);
    }

    @Override
    public ElimGarakSimpleTailor copy() {
        return new ElimGarakSimpleTailor(this);
    }
}
