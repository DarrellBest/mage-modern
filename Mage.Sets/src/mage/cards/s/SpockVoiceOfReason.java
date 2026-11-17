package mage.cards.s;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.dynamicvalue.common.PermanentsOnBattlefieldCount;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.FilterPermanent;
import mage.filter.common.FilterControlledPermanent;
import mage.filter.predicate.permanent.EnteredThisTurnPredicate;
import mage.filter.predicate.permanent.TokenPredicate;

import java.util.UUID;

public final class SpockVoiceOfReason extends CardImpl {

    private static final FilterPermanent filter
            = new FilterControlledPermanent("nontoken permanent you control that entered this turn");

    static {
        filter.add(TokenPredicate.FALSE);
        filter.add(EnteredThisTurnPredicate.instance);
    }

    public SpockVoiceOfReason(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.VULCAN);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.SCIENTIST);
        this.power = new MageInt(3);
        this.toughness = new MageInt(2);

        // {2}, {T}: Draw a card for each nontoken permanent you control that entered this turn.
        Ability ability = new SimpleActivatedAbility(
                new DrawCardSourceControllerEffect(new PermanentsOnBattlefieldCount(filter))
                        .setText("draw a card for each nontoken permanent you control that entered this turn"),
                new GenericManaCost(2)
        );
        ability.addCost(new TapSourceCost());
        this.addAbility(ability);
    }

    private SpockVoiceOfReason(final SpockVoiceOfReason card) {
        super(card);
    }

    @Override
    public SpockVoiceOfReason copy() {
        return new SpockVoiceOfReason(this);
    }
}
