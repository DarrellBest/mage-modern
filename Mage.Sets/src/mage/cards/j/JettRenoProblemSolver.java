package mage.cards.j;

import mage.MageInt;
import mage.abilities.common.BecomesTappedSourceTriggeredAbility;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.condition.Condition;
import mage.abilities.condition.common.SourceMatchesFilterCondition;
import mage.abilities.decorator.ConditionalOneShotEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.counter.AddCountersSourceEffect;
import mage.abilities.effects.keyword.SurveilEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.ComparisonType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.filter.common.FilterCreaturePermanent;
import mage.filter.predicate.mageobject.PowerPredicate;

import java.util.UUID;

public final class JettRenoProblemSolver extends CardImpl {

    private static final FilterCreaturePermanent filter = new FilterCreaturePermanent();

    static {
        filter.add(new PowerPredicate(ComparisonType.MORE_THAN, 3));
    }

    private static final Condition condition = new SourceMatchesFilterCondition(filter);

    public JettRenoProblemSolver(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(1);
        this.toughness = new MageInt(2);

        // Whenever Jett Reno becomes tapped, surveil 1. If her power is 4 or greater, instead draw a card.
        this.addAbility(new BecomesTappedSourceTriggeredAbility(new ConditionalOneShotEffect(
                new DrawCardSourceControllerEffect(1), new SurveilEffect(1), condition,
                "surveil 1. If her power is 4 or greater, instead draw a card"
        )));

        // Whenever you face a dilemma, put a +1/+1 counter on Jett Reno.
        this.addAbility(new FaceDilemmaTriggeredAbility(new AddCountersSourceEffect(CounterType.P1P1.createInstance())));
    }

    private JettRenoProblemSolver(final JettRenoProblemSolver card) {
        super(card);
    }

    @Override
    public JettRenoProblemSolver copy() {
        return new JettRenoProblemSolver(this);
    }
}
