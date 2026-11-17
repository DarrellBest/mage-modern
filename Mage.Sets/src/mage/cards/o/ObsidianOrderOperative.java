package mage.cards.o;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.effects.common.GainLifeEffect;
import mage.abilities.effects.common.LoseLifeOpponentsEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;

import java.util.UUID;

public final class ObsidianOrderOperative extends CardImpl {

    public ObsidianOrderOperative(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{B}");

        this.subtype.add(SubType.CARDASSIAN);
        this.subtype.add(SubType.OFFICER);
        this.subtype.add(SubType.SPY);
        this.power = new MageInt(2);
        this.toughness = new MageInt(5);

        // Whenever you face a dilemma, each opponent loses 1 life and you gain 1 life.
        Ability ability = new FaceDilemmaTriggeredAbility(new LoseLifeOpponentsEffect(1));
        ability.addEffect(new GainLifeEffect(1).concatBy("and"));
        this.addAbility(ability);
    }

    private ObsidianOrderOperative(final ObsidianOrderOperative card) {
        super(card);
    }

    @Override
    public ObsidianOrderOperative copy() {
        return new ObsidianOrderOperative(this);
    }
}
