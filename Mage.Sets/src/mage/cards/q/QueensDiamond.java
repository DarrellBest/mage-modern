package mage.cards.q;

import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.dynamicvalue.common.CountersSourceCount;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.HasteAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.abilities.keyword.VigilanceAbility;
import mage.abilities.mana.ConditionalAnyColorManaAbility;
import mage.abilities.mana.conditional.ConditionalSpellManaBuilder;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.filter.FilterSpell;
import mage.filter.predicate.mageobject.CommanderPredicate;

import java.util.UUID;

public final class QueensDiamond extends CardImpl {

    private static final FilterSpell filter = new FilterSpell("your commander");

    static {
        filter.add(CommanderPredicate.instance);
        filter.add(mage.constants.TargetController.YOU.getOwnerPredicate());
    }

    public QueensDiamond(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{4}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.SPACECRAFT);

        // {T}: Choose a color. Add one mana of that color for each charge counter on Queen's Diamond. Spend this mana only to cast your commander.
        CountersSourceCount charge = new CountersSourceCount(CounterType.CHARGE);
        this.addAbility(new ConditionalAnyColorManaAbility(
                new TapSourceCost(), charge, charge, new ConditionalSpellManaBuilder(filter), true
        ));

        // Station
        this.addAbility(new StationAbility());

        // STATION 8+
        // Flying, vigilance, haste
        // 5/5
        this.addAbility(new StationLevelAbility(8)
                .withLevelAbility(FlyingAbility.getInstance())
                .withLevelAbility(VigilanceAbility.getInstance())
                .withLevelAbility(HasteAbility.getInstance())
                .withPT(5, 5));
    }

    private QueensDiamond(final QueensDiamond card) {
        super(card);
    }

    @Override
    public QueensDiamond copy() {
        return new QueensDiamond(this);
    }
}
