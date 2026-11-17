package mage.cards.m;

import mage.MageInt;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.effects.common.counter.AddCountersSourceEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.VigilanceAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.counters.CounterType;

import java.util.UUID;

public final class MelkotianAdjudicator extends CardImpl {

    public MelkotianAdjudicator(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}");

        this.subtype.add(SubType.MELKOTIAN);
        this.subtype.add(SubType.ADVISOR);
        this.power = new MageInt(2);
        this.toughness = new MageInt(1);

        // Flying, vigilance
        this.addAbility(FlyingAbility.getInstance());
        this.addAbility(VigilanceAbility.getInstance());

        // Whenever you face a dilemma, put a +1/+1 counter on this creature.
        this.addAbility(new FaceDilemmaTriggeredAbility(new AddCountersSourceEffect(CounterType.P1P1.createInstance())));
    }

    private MelkotianAdjudicator(final MelkotianAdjudicator card) {
        super(card);
    }

    @Override
    public MelkotianAdjudicator copy() {
        return new MelkotianAdjudicator(this);
    }
}
