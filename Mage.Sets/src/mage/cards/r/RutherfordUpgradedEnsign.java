package mage.cards.r;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SpellCastControllerTriggeredAbility;
import mage.abilities.effects.common.LookLibraryAndPickControllerEffect;
import mage.abilities.effects.common.counter.AddCountersTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.PutCards;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.filter.FilterSpell;
import mage.filter.StaticFilters;
import mage.target.common.TargetCreaturePermanent;

import java.util.UUID;

public final class RutherfordUpgradedEnsign extends CardImpl {

    private static final FilterSpell spacecraftSpell = new FilterSpell("a Spacecraft spell");

    static {
        spacecraftSpell.add(SubType.SPACECRAFT.getPredicate());
    }

    public RutherfordUpgradedEnsign(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // When Rutherford enters, look at the top six cards of your library. You may reveal an artifact card from
        // among them and put it into your hand. Put the rest on the bottom of your library in a random order.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new LookLibraryAndPickControllerEffect(
                6, 1, StaticFilters.FILTER_CARD_ARTIFACT_AN, PutCards.HAND, PutCards.BOTTOM_RANDOM
        )));

        // Whenever you cast a Spacecraft spell, put a +1/+1 counter on target creature.
        Ability ability = new SpellCastControllerTriggeredAbility(
                new AddCountersTargetEffect(CounterType.P1P1.createInstance()), spacecraftSpell, false);
        ability.addTarget(new TargetCreaturePermanent());
        this.addAbility(ability);
    }

    private RutherfordUpgradedEnsign(final RutherfordUpgradedEnsign card) {
        super(card);
    }

    @Override
    public RutherfordUpgradedEnsign copy() {
        return new RutherfordUpgradedEnsign(this);
    }
}
